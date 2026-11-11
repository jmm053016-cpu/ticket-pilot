package com.example.aiticketassistant.infrastructure.knowledge.bm25;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import org.junit.jupiter.api.Test;

class Bm25SearchAdapterTest {
    private static KnowledgeChunk chunk(String id, String title, String content) {
        return new KnowledgeChunk(id, "DOC", title, "CATALOG", "internal://kb", content);
    }

    @Test
    void productNamePriceQuestionMatchesCatalogChunk() {
        KnowledgeChunk single = chunk("SKIRT", "服装商品目录：高腰A字半身裙",
                "商品编号 CLOTH-SKIRT-007，商品名称 高腰A字半身裙，服装商品，价格 ¥189.00。");

        double score = new Bm25SearchAdapter().score("一件高腰A字半身裙多少钱", single);

        assertThat(score).isGreaterThan(0.0);
    }

    @Test
    void cheapestQuestionBoostsFullCatalogChunk() {
        KnowledgeChunk fullCatalog = chunk("ALL", "服装商品目录：完整价格表",
                "服装商品完整价格表：基础纯棉白色T恤 CLOTH-TEE-001 ¥99.00；蓝色牛津纺衬衫 CLOTH-SHIRT-002 ¥199.00；羊毛混纺大衣 CLOTH-COAT-009 ¥699.00。");
        KnowledgeChunk singleProduct = chunk("COAT", "服装商品目录：羊毛混纺大衣",
                "商品编号 CLOTH-COAT-009，商品名称 羊毛混纺大衣，服装商品，价格 ¥699.00。");

        Bm25SearchAdapter adapter = new Bm25SearchAdapter();
        double fullScore = adapter.score("价格最便宜的衣服是哪一件", fullCatalog);
        double singleScore = adapter.score("价格最便宜的衣服是哪一件", singleProduct);

        assertThat(fullScore).isGreaterThan(singleScore);
    }

    @Test
    void exactPriceQuestionMatchesCatalogChunk() {
        KnowledgeChunk single = chunk("COAT", "服装商品目录：羊毛混纺大衣",
                "商品编号 CLOTH-COAT-009，商品名称 羊毛混纺大衣，服装商品，价格 ¥699.00。");

        double score = new Bm25SearchAdapter().score("价格刚好699的衣服是什么", single);

        assertThat(score).isGreaterThan(0.0);
    }

    @Test
    void budgetQuestionBoostsSummaryChunkEvenWithoutTitleHint() {
        // 预算类问题（不是"最便宜/哪些"）以前拿不到汇总加权，这里覆盖住
        KnowledgeChunk summary = chunk("ALL", "服装商品目录：汇总",
                "T恤 ¥99.00；衬衫 ¥199.00；牛仔裤 ¥299.00；卫衣 ¥259.00；连衣裙 ¥329.00；大衣 ¥699.00。");
        KnowledgeChunk single = chunk("COAT", "服装商品目录：羊毛混纺大衣",
                "商品编号 CLOTH-COAT-009，商品名称 羊毛混纺大衣，服装商品，价格 ¥699.00。");

        Bm25SearchAdapter adapter = new Bm25SearchAdapter();
        double summaryScore = adapter.score("1000元预算可以怎么搭配", summary);
        double singleScore = adapter.score("1000元预算可以怎么搭配", single);

        // 纯命中率下汇总片段会被单品压过去，靠 summaryBoost 才能反过来
        assertThat(summaryScore).isGreaterThan(singleScore);
    }

    @Test
    void summaryChunkIsDetectedByPriceDensityNotOnlyTitle() {
        Bm25SearchAdapter adapter = new Bm25SearchAdapter();
        assertThat(adapter.isSummaryChunk(chunk("A", "服装商品目录：完整价格表", "随便写点"))).isTrue();
        assertThat(adapter.isSummaryChunk(chunk("B", "服装商品目录：汇总",
                "¥99.00；¥199.00；¥299.00；¥259.00；¥329.00"))).isTrue();
        assertThat(adapter.isSummaryChunk(chunk("C", "服装商品目录：单品", "价格 ¥699.00。"))).isFalse();
    }

    @Test
    void nonCatalogQuestionGetsNoSummaryBoost() {
        // 售后问题里也有"哪些/有什么"这类汇总线索，但它跟服装价格表毫无关系。
        // 以前只要含"哪些"就给汇总片段 +0.4，把整张价格表顶到第一位（实测 0.4 vs 真实命中 0.036），
        // 直答路径据此判断"有没有资料"会被彻底带偏。
        KnowledgeChunk summary = chunk("ALL", "服装商品目录：完整价格表",
                "服装商品完整价格表：基础纯棉白色T恤 CLOTH-TEE-001 ¥99.00；蓝色牛津纺衬衫 CLOTH-SHIRT-002 ¥199.00；"
                        + "直筒水洗牛仔裤 CLOTH-JEANS-003 ¥299.00；黑色连帽卫衣 CLOTH-HOODIE-004 ¥259.00；"
                        + "碎花雪纺连衣裙 CLOTH-DRESS-005 ¥329.00；羊毛混纺大衣 CLOTH-COAT-009 ¥699.00。");

        Bm25SearchAdapter adapter = new Bm25SearchAdapter();

        assertThat(adapter.score("支持哪些支付方式", summary))
                .as("非商品类问题不该白拿 +0.4 的汇总加成")
                .isLessThan(0.4);
        assertThat(adapter.score("1000元预算可以怎么搭配", summary))
                .as("商品/预算类问题仍然享受汇总加成")
                .isGreaterThanOrEqualTo(0.4);
    }
}
