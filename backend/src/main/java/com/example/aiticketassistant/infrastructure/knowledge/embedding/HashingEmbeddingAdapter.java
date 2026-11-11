package com.example.aiticketassistant.infrastructure.knowledge.embedding;

import com.example.aiticketassistant.domain.knowledge.EmbeddingPort;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地确定性向量：字符 n-gram / 数字特征的 **TF-IDF 加权特征哈希**，L2 归一化。
 *
 * <p>**为什么用它当默认实现**：RAG 的向量那一路以前是空的（`ChromaClient.searchIds()` 恒返回 `List.of()`），
 * 于是 `finalScore` 实际等于 BM25 分数 —— 换一种问法就召回不到。真正的语义向量需要模型，
 * 而本项目要能离线跑测试、离线演示，所以这里先用"不需要任何外部服务"的向量把**融合管线**搭起来。
 *
 * <p>两个细节是靠实测定下来的，别轻易改回去：
 * <ol>
 *   <li><b>数字要单独成特征</b>：中文句子里的数字常和汉字连在一起（"价格刚好699的衣服"），
 *       只抽汉字会把 699 整个丢掉，于是"699"这个最有区分度的特征在向量里根本不存在
 *       （实测：这样「七百块的衣服」的向量冠军是¥229的毛衣）。</li>
 *   <li><b>必须按 IDF 加权</b>：语料里每条片段都有「商品编号/服装商品/价格」，
 *       这些 ubiquitous n-gram 会让余弦变成"谁跟谁都像"，实测无关片段能拿到 0.13 的最高分。
 *       IDF 之后真正稀有的特征（699、大衣、羽绒）才起作用。</li>
 * </ol>
 *
 * <p>**它不是什么**：不是语义模型。「七百」和「699」的对应靠查询侧数字归一 + BM25 的数值邻近加成；
 * 同义词（「外套」→「大衣」）靠 {@code QueryRewriter} 扩展。要真语义向量就把
 * `assistant.retrieval.embedding-provider` 设成 `openai-compatible`
 * （见 {@link OpenAiCompatibleEmbeddingAdapter}），本类同时作为它的降级兜底。
 */
@Component
@ConditionalOnProperty(name = "assistant.retrieval.embedding-provider", havingValue = "hash", matchIfMissing = true)
public class HashingEmbeddingAdapter implements EmbeddingPort {
    /** 512 维：几百个特征时碰撞率可接受，且内存/计算都可忽略。 */
    private static final int DIMENSIONS = 512;
    /** 中文 n-gram 长度：2 字能抓"卫衣/大衣"，3 字能抓"连帽卫"，再多收益递减。 */
    private static final int MIN_GRAM = 2;
    private static final int MAX_GRAM = 3;
    private static final double DEFAULT_IDF = 1.0;

    /** 特征 → IDF 权重（{@link #fit} 之前为空，等价于不加权）。 */
    private volatile Map<String, Double> idf = Map.of();

    @Override
    public void fit(List<KnowledgeChunk> corpus) {
        if (corpus == null || corpus.isEmpty()) {
            this.idf = Map.of();
            return;
        }
        int documentCount = corpus.size();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (KnowledgeChunk chunk : corpus) {
            for (String feature : featuresOf(chunk)) {
                documentFrequency.merge(feature, 1, Integer::sum);
            }
        }
        Map<String, Double> weights = new HashMap<>(documentFrequency.size() * 2);
        documentFrequency.forEach((feature, frequency) ->
                weights.put(feature, Math.log(1.0 + (double) documentCount / frequency)));
        this.idf = Map.copyOf(weights);
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }
        Map<String, Double> current = idf;
        for (String feature : features(normalize(text))) {
            vector[Math.floorMod(feature.hashCode(), DIMENSIONS)] += current.getOrDefault(feature, DEFAULT_IDF).floatValue();
        }
        return l2Normalize(vector);
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /** 当前权重表大小（测试用：确认 fit 生效）。 */
    int vocabularySize() {
        return idf.size();
    }

    private static List<String> featuresOf(KnowledgeChunk chunk) {
        return features(normalize((chunk.title() == null ? "" : chunk.title()) + " "
                + (chunk.content() == null ? "" : chunk.content())));
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9.]+", " ").trim();
    }

    /**
     * 特征抽取：中文 2~3 字 n-gram + 拉丁/数字整词 + **汉字串里的数字**（见类注释第 1 条）。
     * 包级可见，供测试直接验证口径。
     */
    static List<String> features(String normalized) {
        Set<String> features = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            // 数字/字母单独成特征（"价格刚好699的衣服" → 699）
            for (String numeric : token.split("[^a-z0-9.]+")) {
                if (!numeric.isBlank()) {
                    features.add(numeric.replaceAll("\\.0+$", ""));
                }
            }
            String han = token.replaceAll("[^\\p{IsHan}]", "");
            if (han.isEmpty()) {
                continue;
            }
            // 单字词（"鞋""包"）在 2-gram 下会整体消失，单独补一条
            if (han.length() == 1) {
                features.add(han);
            }
            for (int size = MIN_GRAM; size <= MAX_GRAM; size++) {
                for (int i = 0; i + size <= han.length(); i++) {
                    features.add(han.substring(i, i + size));
                }
            }
        }
        return new ArrayList<>(features);
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

    /** 供测试断言"IDF 让 ubiquitous 特征变小"。 */
    Map<String, Double> weights() {
        return idf;
    }
}
