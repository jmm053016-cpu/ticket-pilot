package com.example.aiticketassistant.infrastructure.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 本地向量（TF-IDF 特征哈希）的口径。
 *
 * <p>三个"改回去就会悄悄退化"的点都在这里锁住：数字要单独成特征、必须按 IDF 加权、
 * 同文本余弦为 1（L2 归一化的前提）。
 */
class HashingEmbeddingAdapterTest {
    private static final List<KnowledgeChunk> CORPUS = List.of(
            new KnowledgeChunk("COAT", "DOC", "服装商品目录：羊毛混纺大衣", "CATALOG", "internal://c",
                    "商品编号 CLOTH-COAT-009，商品名称 羊毛混纺大衣，服装商品，价格 ¥699.00。"),
            new KnowledgeChunk("TEE", "DOC", "服装商品目录：基础纯棉白色T恤", "CATALOG", "internal://t",
                    "商品编号 CLOTH-TEE-001，商品名称 基础纯棉白色T恤，服装商品，价格 ¥99.00。"));

    private static HashingEmbeddingAdapter fitted() {
        HashingEmbeddingAdapter adapter = new HashingEmbeddingAdapter();
        adapter.fit(CORPUS);
        return adapter;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }

    @Test
    void sameTextIsPerfectlySimilarAndEmptyIsZeroVector() {
        HashingEmbeddingAdapter adapter = fitted();

        assertThat(cosine(adapter.embed("羊毛混纺大衣"), adapter.embed("羊毛混纺大衣"))).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(cosine(adapter.embed(""), adapter.embed("羊毛混纺大衣"))).isEqualTo(0.0);
        assertThat(adapter.dimensions()).isEqualTo(512);
    }

    @Test
    void digitsInsideChineseSentencesBecomeTheirOwnFeature() {
        // 「价格刚好699的衣服」里的 699 必须成为特征，否则向量根本分不出哪件是 699 的
        assertThat(HashingEmbeddingAdapter.features("价格刚好699的衣服")).contains("699");
    }

    @Test
    void idfDownweightsFeaturesThatAppearEverywhere() {
        HashingEmbeddingAdapter adapter = fitted();

        double ubiquitous = adapter.weights().getOrDefault("服装", 1.0);   // 两条片段都有
        double rare = adapter.weights().getOrDefault("699", 1.0);          // 只有一条有

        assertThat(rare).as("稀有特征权重必须显著高于 ubiquitous 特征").isGreaterThan(ubiquitous);
    }

    @Test
    void relevantTextScoresHigherThanUnrelatedOne() {
        HashingEmbeddingAdapter adapter = fitted();
        float[] query = adapter.embed("699 的大衣");

        assertThat(cosine(query, adapter.embed("商品名称 羊毛混纺大衣，价格 ¥699.00")))
                .isGreaterThan(cosine(query, adapter.embed("商品名称 基础纯棉白色T恤，价格 ¥99.00")));
    }

    @Test
    void fitIsOptionalAndEmbeddingStillWorksBeforeIt() {
        HashingEmbeddingAdapter adapter = new HashingEmbeddingAdapter();

        assertThat(cosine(adapter.embed("大衣"), adapter.embed("大衣"))).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }
}
