package com.example.aiticketassistant.infrastructure.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.domain.knowledge.EmbeddingPort;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchResult;
import com.example.aiticketassistant.infrastructure.knowledge.bm25.Bm25SearchAdapter;
import com.example.aiticketassistant.infrastructure.knowledge.embedding.HashingEmbeddingAdapter;
import com.example.aiticketassistant.infrastructure.knowledge.query.QueryRewriter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 语义向量接上之后，**"字面完全不像"的问题也能召回** —— 这是哈希向量做不到、真模型才做得到的那部分能力。
 *
 * <p>这里用一个人造的"语义端口"代替真模型（把意思相近的表述映射到同一个方向），
 * 目的是锁住**管线**：只要 {@link EmbeddingPort} 给出的是语义向量，检索层就能靠它把正确答案捞出来，
 * 而且 {@code matchReason} 里能看到 {@code vec#}。真模型的语义质量由模型决定，不在单测里断言。
 */
class HybridKnowledgeSearchRepositorySemanticTest {
    private static final List<KnowledgeChunk> CORPUS = List.of(
            new KnowledgeChunk("COAT", "DOC", "服装商品目录：羊毛混纺大衣", "CATALOG", "internal://c",
                    "商品编号 CLOTH-COAT-009，商品名称 羊毛混纺大衣，服装商品，价格 ¥699.00。"),
            new KnowledgeChunk("HOODIE", "DOC", "服装商品目录：黑色连帽卫衣", "CATALOG", "internal://h",
                    "商品编号 CLOTH-HOODIE-004，商品名称 黑色连帽卫衣，服装商品，价格 ¥259.00。"));

    /** 「冬天穿的那件长款」和「羊毛混纺大衣」字面几乎无交集，但意思是一类东西。 */
    private static final String SEMANTIC_QUERY = "冬天穿的那件长款的多少钱";

    /** 模拟语义模型：把同类表述映射到同一方向（真模型靠训练得到，这里靠关键词表代替）。 */
    private static final EmbeddingPort SEMANTIC_MODEL = new EmbeddingPort() {
        @Override
        public float[] embed(String text) {
            boolean coat = text.contains("大衣") || text.contains("冬天") || text.contains("长款")
                    || text.contains("外套") || text.contains("呢子");
            boolean hoodie = text.contains("卫衣") || text.contains("连帽");
            return new float[]{coat ? 1.0f : 0.05f, hoodie ? 1.0f : 0.05f, 0.2f};
        }

        @Override
        public int dimensions() {
            return 3;
        }
    };

    private static HybridKnowledgeSearchRepository repository(EmbeddingPort port) {
        return new HybridKnowledgeSearchRepository(
                () -> CORPUS, new Bm25SearchAdapter(), new QueryRewriter(), port);
    }

    @Test
    void semanticVectorsRescueQueriesWithNoLexicalOverlap() {
        List<KnowledgeSearchResult> results = repository(SEMANTIC_MODEL).search(SEMANTIC_QUERY, 2);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunk().id())
                .as("字面没有交集，只能靠语义向量把大衣捞出来")
                .isEqualTo("COAT");
        assertThat(results.get(0).matchReason()).contains("vec#");
        assertThat(results.get(0).vectorScore()).isGreaterThan(0.5);
    }

    @Test
    void hashingVectorsCannotDoThisWhichIsWhyTheSemanticOptionMatters() {
        // 同一个问题，用默认的本地哈希向量：字面只重合"服装/价格"这类泛词，
        // 正确的大衣排在不相干的卫衣后面（0.014 < 0.028），而且全部低于直答路径的 0.10 相关性门槛 —— 等于没检到。
        List<KnowledgeSearchResult> results = repository(new HashingEmbeddingAdapter()).search(SEMANTIC_QUERY, 2);

        assertThat(results).allSatisfy(result -> assertThat(result.finalScore()).isLessThan(0.10));
        assertThat(results.get(0).chunk().id())
                .as("哈希向量排不出语义，冠军是噪音而不是大衣")
                .isNotEqualTo("COAT");
    }

    @Test
    void semanticRouteAlsoHelpsOnceLexicalRouteIsSilent() {
        // 「帽衫」靠同义词表也能到，但「连帽」这个词在片段里有 → 两路都该命中，且融合分明显高于纯噪音
        List<KnowledgeSearchResult> results = repository(SEMANTIC_MODEL).search("连帽的衣服多少钱", 2);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunk().id()).isEqualTo("HOODIE");
        assertThat(results.get(0).finalScore()).isGreaterThan(0.1);
    }
}
