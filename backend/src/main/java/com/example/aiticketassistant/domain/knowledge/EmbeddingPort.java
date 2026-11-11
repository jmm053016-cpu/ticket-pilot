package com.example.aiticketassistant.domain.knowledge;

import java.util.List;

/**
 * 把文本转成向量的端口（RAG 的"向量那一路"）。
 *
 * <p>为什么要端口而不是直接调某个模型：检索质量要能**离线评测**（见 {@code RetrievalEvaluationTest}），
 * 而测试环境不能依赖网络与 API Key。默认实现是确定性的本地哈希向量
 * （{@code HashingEmbeddingAdapter}）；要接真正的语义模型（DashScope / OpenAI 兼容 embedding），
 * 实现这个接口并声明为 Bean 即可，检索融合逻辑一行都不用改。
 *
 * <p>约定：同一实现必须**确定性**（同文本同向量），返回值长度固定为 {@link #dimensions()}，
 * 且应当已做 L2 归一化 —— 这样余弦相似度就是点积，融合时不用再反复算模长。
 */
public interface EmbeddingPort {

    /**
     * 给实现一个"看一遍语料"的机会（可选）。
     *
     * <p>TF-IDF 类实现用它统计文档频率（否则「商品/服装」这种到处都有的 n-gram 会淹没真正的关键词）；
     * 真模型实现直接忽略这个钩子。检索层在语料变化时调用一次。
     */
    default void fit(List<KnowledgeChunk> corpus) {
        // 无状态实现不用做事
    }

    /** 文本向量（L2 归一化后）。 */
    float[] embed(String text);

    /** 向量维度，用于校验与日志。 */
    int dimensions();
}
