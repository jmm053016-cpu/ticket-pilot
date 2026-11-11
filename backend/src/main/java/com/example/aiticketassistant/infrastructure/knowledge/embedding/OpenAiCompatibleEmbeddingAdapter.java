package com.example.aiticketassistant.infrastructure.knowledge.embedding;

import com.example.aiticketassistant.domain.knowledge.EmbeddingPort;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.infrastructure.config.AssistantProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * **真语义向量**：任何 OpenAI 兼容的 {@code POST /embeddings} 服务（DashScope/Qwen、SiliconFlow、Ollama、vLLM…）。
 *
 * <p>为什么需要它：默认的 {@link HashingEmbeddingAdapter} 是"字面哈希"，只能补"词序/组合不同"的召回，
 * 同义（外套↔大衣）靠同义词表、口语数字（七百↔699）靠数字归一+邻近加成。
 * 换成真模型后，语义相近的表述不用写进同义词表也能召回。
 *
 * <p>为什么用"OpenAI 兼容"而不是某个厂商 SDK：DeepSeek 目前没有 embeddings 接口，而 DashScope 的
 * compatible-mode、Ollama、SiliconFlow 都提供同一个 {@code /embeddings} 形状 —— 一套实现能覆盖全部，
 * 换服务商只改三个配置项（base-url / model / api-key），代码一行不动。
 *
 * <p>三个工程上的决定：
 * <ol>
 *   <li><b>语料批量预嵌入</b>：{@link #fit} 在语料变化时按批（{@value #BATCH_SIZE} 条/次）一次性算完并缓存，
 *       于是运行期一次查询只花 1 次网络往返；否则首问会把 30+ 条片段逐条打一遍，慢到不可用。</li>
 *   <li><b>失败就整体降级</b>：没配 key、网络不通、5xx、返回条数不匹配 —— 一律打一条 warn，然后**永久**切回
 *       {@link HashingEmbeddingAdapter}（并清空缓存，保证全语料同一向量空间）。
 *       检索是增强能力，不能因为 embedding 服务抖动就让回答失败。</li>
 *   <li><b>自己再做一次 L2 归一化</b>：不同服务商对"是否已归一"的承诺不一致，
 *       检索层用点积当余弦，必须自己保证单位长度。</li>
 * </ol>
 *
 * <p>启用方式：`EMBEDDING_PROVIDER=openai-compatible`（另需 `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` /
 * `EMBEDDING_API_KEY`，默认指向 DashScope 的 text-embedding-v3 并复用 `QWEN_API_KEY`）。见 §6.5 ⑤。
 */
@Component
@ConditionalOnProperty(name = "assistant.retrieval.embedding-provider", havingValue = "openai-compatible")
public class OpenAiCompatibleEmbeddingAdapter implements EmbeddingPort {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingAdapter.class);
    /** 批量预嵌入的批大小：DashScope 单次上限 10，取小值兼容各家。 */
    static final int BATCH_SIZE = 10;
    /** 缓存上限：语料几百条 + 少量查询文本，留足余量即可。 */
    private static final int CACHE_LIMIT = 4096;

    private final WebClient webClient;
    private final AssistantProperties.Retrieval retrieval;
    private final Duration timeout;
    /** 降级目标：本地哈希向量（自己 new，不依赖 Bean —— 本适配器启用时哈希实现并没有注册为 Bean）。 */
    private final HashingEmbeddingAdapter fallback = new HashingEmbeddingAdapter();
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    private final AtomicInteger failedCalls = new AtomicInteger();

    public OpenAiCompatibleEmbeddingAdapter(WebClient.Builder builder, AssistantProperties properties) {
        this.retrieval = properties.retrieval();
        this.timeout = properties.ai() == null || properties.ai().timeout() == null
                ? Duration.ofSeconds(10)
                : properties.ai().timeout();
        this.webClient = builder
                .baseUrl(retrieval.embeddingBaseUrl())
                .defaultHeader("Authorization", "Bearer " + (retrieval.embeddingApiKey() == null ? "" : retrieval.embeddingApiKey()))
                .build();
    }

    @Override
    public void fit(List<KnowledgeChunk> corpus) {
        if (corpus == null || corpus.isEmpty() || degraded.get()) {
            return;
        }
        cache.clear();
        List<String> texts = new ArrayList<>();
        for (KnowledgeChunk chunk : corpus) {
            texts.add(chunkText(chunk));
        }
        long startedAt = System.nanoTime();
        int embedded = 0;
        for (int from = 0; from < texts.size(); from += BATCH_SIZE) {
            List<String> batch = texts.subList(from, Math.min(from + BATCH_SIZE, texts.size()));
            List<float[]> vectors = requestEmbeddings(batch);
            if (vectors == null) {
                return;   // 已降级
            }
            for (int i = 0; i < batch.size(); i++) {
                put(batch.get(i), vectors.get(i));
                embedded++;
            }
        }
        log.info("语义向量已就绪：model={} dimensions={} 片段={} 批次={} 耗时={}ms",
                retrieval.embeddingModel(), retrieval.embeddingDimensions(), embedded,
                (texts.size() + BATCH_SIZE - 1) / BATCH_SIZE,
                (System.nanoTime() - startedAt) / 1_000_000);
    }

    @Override
    public float[] embed(String text) {
        if (degraded.get()) {
            return fallback.embed(text);
        }
        if (text == null || text.isBlank()) {
            return new float[dimensions()];
        }
        float[] cached = cache.get(text);
        if (cached != null) {
            return cached;
        }
        List<float[]> vectors = requestEmbeddings(List.of(text));
        if (vectors == null || vectors.isEmpty()) {
            return fallback.embed(text);
        }
        put(text, vectors.get(0));
        return vectors.get(0);
    }

    @Override
    public int dimensions() {
        // 降级后整个向量空间都变成哈希向量，维度也必须跟着报（检索层用长度校验对齐）
        return degraded.get() ? fallback.dimensions() : (int) retrieval.embeddingDimensions();
    }

    /** 是否已经降级到本地哈希向量（测试与诊断用）。 */
    public boolean degraded() {
        return degraded.get();
    }

    private String chunkText(KnowledgeChunk chunk) {
        return (chunk.title() == null ? "" : chunk.title()) + " " + (chunk.content() == null ? "" : chunk.content());
    }

    private void put(String text, float[] vector) {
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(text, vector);
    }

    /**
     * 请求一次 embeddings；失败返回 {@code null} 并**永久降级**。
     *
     * <p>返回顺序按上游的 {@code index} 还原 —— 有些服务不保证按输入顺序返回，
     * 张冠李戴的向量比没有向量更难查。
     *
     * <p><b>为什么要 subscribeOn + toFuture 而不是直接 block()</b>：这个方法会被
     * `/knowledge/search`（WebFlux，跑在 Netty 事件循环上）和直答路径调用，
     * Reactor 在事件循环线程上禁止 {@code block()}（会抛 "block()/blockFirst() ... not supported"，
     * 2026-11-04 真机启动时就是这么静默降级的）。所以真正干活的是 boundedElastic 线程，
     * 调用方只是等一个 future —— 语义等价的同步语义，但不触发事件循环保护。
     */
    private List<float[]> requestEmbeddings(List<String> inputs) {
        try {
            JsonNode response = Mono.fromCallable(() -> webClient.post()
                            .uri("/embeddings")
                            .bodyValue(Map.of(
                                    "model", retrieval.embeddingModel(),
                                    "input", inputs,
                                    "encoding_format", "float"))
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block(timeout))
                    .subscribeOn(Schedulers.boundedElastic())
                    .toFuture()
                    .get(timeout.toMillis() + 1000L, TimeUnit.MILLISECONDS);
            List<float[]> vectors = parse(response, inputs.size());
            if (vectors == null) {
                degrade("返回体缺少 data[].embedding 或条数不符");
            }
            return vectors;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            degrade("请求被中断");
            return null;
        } catch (Exception ex) {
            degrade(ex.getMessage());
            return null;
        }
    }

    /** 解析 OpenAI 风格响应：{@code {"data":[{"index":0,"embedding":[...]}, ...]}}。 */
    private List<float[]> parse(JsonNode response, int expected) {
        if (response == null) {
            return null;
        }
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != expected) {
            return null;
        }
        List<JsonNode> items = new ArrayList<>();
        data.forEach(items::add);
        items.sort(Comparator.comparingInt(item -> item.path("index").asInt(0)));
        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode item : items) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                return null;
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(l2Normalize(vector));
        }
        return vectors;
    }

    private void degrade(String reason) {
        if (degraded.compareAndSet(false, true)) {
            log.warn("语义向量不可用，已整体降级到本地哈希向量（检索仍可工作，只是少一路语义召回）：{}", reason);
        } else if (failedCalls.incrementAndGet() % 50 == 1) {
            log.warn("语义向量仍不可用（已降级），第 {} 次失败：{}", failedCalls.get(), reason);
        }
        cache.clear();
    }

    private static float[] l2Normalize(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        if (sum == 0.0) {
            return vector;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= norm;
        }
        return vector;
    }
}
