package com.example.aiticketassistant.infrastructure.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.infrastructure.config.AssistantProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * OpenAI 兼容语义向量适配器：HTTP 契约 + 缓存 + 降级。
 *
 * <p>用 JDK 自带的 {@link HttpServer} 起一个本地桩，**真的走一遍 HTTP**（请求头、批处理、响应解析），
 * 而不是 mock WebClient —— 这一层的坑（上游不按输入顺序返回、返回条数不对、服务 500）只有真请求才能测出来。
 * 语义质量本身是模型的责任，不在单测里断言。
 */
class OpenAiCompatibleEmbeddingAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;

    @BeforeEach
    void startStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", this::handle);
        server.start();
    }

    @AfterEach
    void stopStubServer() {
        server.stop(0);
    }

    /** 桩实现：按文本里的关键词给一个 3 维向量，并**故意倒序返回**（上游不保证顺序）。 */
    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
        List<String> inputs = new ArrayList<>();
        request.path("input").forEach(node -> inputs.add(node.asText()));

        List<String> items = new ArrayList<>();
        for (int i = inputs.size() - 1; i >= 0; i--) {   // 倒序：考验按 index 还原
            String text = inputs.get(i);
            double coat = text.contains("大衣") ? 1.0 : 0.1;
            double tee = text.contains("T恤") ? 1.0 : 0.1;
            items.add("{\"index\":%d,\"embedding\":[%s,0.5,%s]}".formatted(i, coat, tee));
        }
        byte[] body = ("{\"data\":[" + String.join(",", items) + "]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private OpenAiCompatibleEmbeddingAdapter adapter(String apiKey) {
        AssistantProperties.Ai ai = new AssistantProperties.Ai(
                "fallback", null, null, 0L, null, null, null, 0L, Duration.ofSeconds(5));
        AssistantProperties.Retrieval retrieval = new AssistantProperties.Retrieval(
                "openai-compatible",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "stub-embedding",
                apiKey,
                3);
        return new OpenAiCompatibleEmbeddingAdapter(
                WebClient.builder(), new AssistantProperties(ai, retrieval, null, null, null));
    }

    @Test
    void fitPreEmbedsTheCorpusInBatchesAndNormalizesVectors() {
        OpenAiCompatibleEmbeddingAdapter adapter = adapter("sk-test");

        adapter.fit(List.of(
                new KnowledgeChunk("COAT", "DOC", "服装商品目录：羊毛混纺大衣", "CATALOG", "internal://c", "价格 ¥699.00。"),
                new KnowledgeChunk("TEE", "DOC", "服装商品目录：白色T恤", "CATALOG", "internal://t", "价格 ¥99.00。")));

        assertThat(requests.get()).as("两条片段一批打完，不该逐条请求").isEqualTo(1);
        float[] coat = adapter.embed("服装商品目录：羊毛混纺大衣 价格 ¥699.00。");
        float[] tee = adapter.embed("服装商品目录：白色T恤 价格 ¥99.00。");

        assertThat(norm(coat)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        // 倒序返回被按 index 还原：大衣那条的第 0 维该比 T恤 大，第 2 维反之
        assertThat(coat[0]).isGreaterThan(tee[0]);
        assertThat(tee[2]).isGreaterThan(coat[2]);
        assertThat(adapter.dimensions()).isEqualTo(3);
        assertThat(adapter.degraded()).isFalse();
    }

    @Test
    void repeatedQueriesAreServedFromCache() {
        OpenAiCompatibleEmbeddingAdapter adapter = adapter("sk-test");

        adapter.embed("羊毛混纺大衣");
        int afterFirst = requests.get();
        adapter.embed("羊毛混纺大衣");

        assertThat(requests.get()).as("同一文本不该重复打网络").isEqualTo(afterFirst);
    }

    @Test
    void serverFailureDegradesToLocalHashingVectors() {
        OpenAiCompatibleEmbeddingAdapter adapter = adapter("sk-test");
        status = 500;

        float[] vector = adapter.embed("羊毛混纺大衣");

        assertThat(adapter.degraded()).isTrue();
        assertThat(vector).hasSize(adapter.dimensions());
        assertThat(adapter.dimensions()).as("降级后整个向量空间是哈希向量").isEqualTo(512);
        assertThat(norm(vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        // 降级是"一次就永久"：再问也不该再打已经坏掉的服务
        int before = requests.get();
        adapter.embed("白色T恤");
        assertThat(requests.get()).isEqualTo(before);
    }

    private static double norm(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }
}
