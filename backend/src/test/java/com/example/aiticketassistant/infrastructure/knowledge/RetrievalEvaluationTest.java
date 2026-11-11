package com.example.aiticketassistant.infrastructure.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchResult;
import com.example.aiticketassistant.infrastructure.knowledge.bm25.Bm25SearchAdapter;
import com.example.aiticketassistant.infrastructure.knowledge.embedding.HashingEmbeddingAdapter;
import com.example.aiticketassistant.infrastructure.knowledge.query.QueryRewriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 检索评测集（回归用）。
 *
 * <p>为什么要有它：检索质量是"问法一变就悄悄退化"的东西 —— 之前就出现过
 * 「1000元预算可以怎么搭配」一条都检不到、「能买几件」只拿到 3 个单品（漏掉整张价格表）。
 * 这里用**真实语料**锁住每类问法必须召回哪些片段。
 *
 * <p>语料刻意不连数据库：走 {@code KnowledgeChunkSource} 端口，改检索算法时这里会立刻响。
 * 数据按 V3 + V8 种子生成（30 件商品 + 整张价格表 + 3 条售后 FAQ），
 * 这样加商品时只要改下面这张表，评测集跟着长；片段 id / 内容格式必须与迁移一致（有测试盯着）。
 */
class RetrievalEvaluationTest {
    /** 与 V3 + V8 种子一致：商品编号 / 商品名称 / 价格。加商品时请同步这张表。 */
    private static final String[][] CATALOG = {
            {"CLOTH-TEE-001", "基础纯棉白色T恤", "99.00"},
            {"CLOTH-SHIRT-002", "蓝色牛津纺衬衫", "199.00"},
            {"CLOTH-JEANS-003", "直筒水洗牛仔裤", "299.00"},
            {"CLOTH-HOODIE-004", "黑色连帽卫衣", "259.00"},
            {"CLOTH-DRESS-005", "碎花雪纺连衣裙", "329.00"},
            {"CLOTH-JACKET-006", "轻薄防风夹克", "399.00"},
            {"CLOTH-SKIRT-007", "高腰A字半身裙", "189.00"},
            {"CLOTH-POLO-008", "商务休闲Polo衫", "169.00"},
            {"CLOTH-COAT-009", "羊毛混纺大衣", "699.00"},
            {"CLOTH-SWEATER-010", "米色针织毛衣", "229.00"},
            {"CLOTH-TEE-011", "白色圆领短袖T恤", "129.00"},
            {"CLOTH-TEE-012", "条纹短袖T恤", "149.00"},
            {"CLOTH-SHIRT-013", "白色免烫衬衫", "249.00"},
            {"CLOTH-SHIRT-014", "格纹法兰绒衬衫", "219.00"},
            {"CLOTH-HOODIE-015", "灰色拉链连帽卫衣", "279.00"},
            {"CLOTH-SWEATER-016", "燕麦色高领毛衣", "239.00"},
            {"CLOTH-CARDIGAN-017", "深灰针织开衫", "269.00"},
            {"CLOTH-JEANS-018", "浅蓝阔腿牛仔裤", "319.00"},
            {"CLOTH-PANTS-019", "黑色直筒休闲裤", "259.00"},
            {"CLOTH-SHORTS-020", "卡其五分短裤", "139.00"},
            {"CLOTH-JACKET-021", "军绿工装夹克", "459.00"},
            {"CLOTH-WINDBREAKER-022", "米白风衣外套", "529.00"},
            {"CLOTH-DOWN-023", "黑色短款羽绒服", "899.00"},
            {"CLOTH-COAT-024", "驼色双面呢大衣", "1299.00"},
            {"CLOTH-DRESS-025", "法式方领连衣裙", "369.00"},
            {"CLOTH-SKIRT-026", "百褶中长半身裙", "179.00"},
            {"CLOTH-SNEAKER-027", "白色厚底运动鞋", "399.00"},
            {"CLOTH-CANVAS-028", "藏青帆布鞋", "199.00"},
            {"CLOTH-BAG-029", "米色帆布双肩包", "299.00"},
            {"CLOTH-SCARF-030", "灰色羊毛围巾", "109.00"}};

    private static final List<KnowledgeChunk> CORPUS = corpus();

    private static List<KnowledgeChunk> corpus() {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        chunks.add(catalog("ALL", "服装商品目录：完整价格表", summaryContent()));
        for (String[] row : CATALOG) {
            chunks.add(catalog(suffixOf(row[0]), "服装商品目录：" + row[1], singleContent(row)));
        }
        chunks.add(new KnowledgeChunk("ORDER-001-1", "DOC-ORDER-001", "订单状态说明", "ORDER",
                "internal://kb/order-status",
                "订单 FAILED 表示业务处理失败；支付 PAID + 订单 FAILED 是典型补单或退款核验场景。"));
        chunks.add(new KnowledgeChunk("PAY-001-1", "DOC-PAY-001", "支付成功但订单未生成处理规范", "PAYMENT",
                "internal://kb/payment-order-missing",
                "支付成功但订单没生成：先查询订单与支付流水；如果支付状态 PAID 且订单状态 FAILED，创建高优先级支付工单并进入补偿流程。"));
        chunks.add(new KnowledgeChunk("PAY-002-1", "DOC-PAY-002", "支付失败工单创建 FAQ", "PAYMENT",
                "internal://faq/payment-failed-ticket",
                "创建支付失败工单需要标题、客户 ID、订单号、支付渠道和失败截图；优先级通常为 HIGH。"));
        return List.copyOf(chunks);
    }

    /** 单品片段格式，与 V5/V8 迁移一行不差（PriceCatalogService 靠它解析）。 */
    private static String singleContent(String[] row) {
        return "商品编号 " + row[0] + "，商品名称 " + row[1] + "，服装商品，价格 ¥" + row[2] + "。";
    }

    /** 汇总片段格式，与 V8 的 UPDATE 一致。 */
    private static String summaryContent() {
        StringBuilder text = new StringBuilder("服装商品完整价格表：");
        for (String[] row : CATALOG) {
            text.append(row[1]).append(' ').append(row[0]).append(" ¥").append(row[2]).append('；');
        }
        return text.append('。').toString();
    }

    /** CHK-CATALOG-CLOTHING-TEE-001 → TEE-001 */
    private static String suffixOf(String productId) {
        return productId.substring("CLOTH-".length());
    }

    private static KnowledgeChunk catalog(String suffix, String title, String content) {
        return new KnowledgeChunk("CHK-CATALOG-CLOTHING-" + suffix, "DOC-CATALOG-CLOTHING", title, "CATALOG",
                "internal://catalog/clothing#" + suffix, content);
    }

    private static List<String> search(String query, int limit) {
        return repository().search(query, limit).stream().map(result -> result.chunk().id()).toList();
    }

    private static HybridKnowledgeSearchRepository repository() {
        return new HybridKnowledgeSearchRepository(
                () -> CORPUS, new Bm25SearchAdapter(), new QueryRewriter(), new HashingEmbeddingAdapter());
    }

    @Test
    void exactPriceQuestionFindsOnlyTheRightProduct() {
        List<String> ids = search("价格刚好699的衣服是什么", 5);
        List<KnowledgeSearchResult> ranked = repository().search("价格刚好699的衣服是什么", 5);

        assertThat(ids).first().isEqualTo("CHK-CATALOG-CLOTHING-COAT-009");
        // 冠军要领先：第 2 名是"完整价格表"（它也含 699，属于合理候选），
        // 但第 3 名必须是纯噪音级别 —— 否则 KnowledgeAgent 的"数字精确过滤"都没法兜。
        assertThat(ranked.get(0).finalScore()).isGreaterThan(ranked.get(1).finalScore());
        assertThat(ranked.get(0).finalScore()).isGreaterThan(ranked.get(2).finalScore() * 2);
    }

    @Test
    void everyCatalogItemIsFoundByItsOwnName() {
        // 目录扩到 30 件后，最容易坏的是"某件商品被同类商品的片段压掉、根本检不到"
        for (String[] row : CATALOG) {
            assertThat(search(row[1] + "多少钱", 5))
                    .as("问「%s多少钱」必须召回它自己的片段", row[1])
                    .contains("CHK-CATALOG-CLOTHING-" + suffixOf(row[0]));
        }
    }

    @Test
    void namedProductQuestionFindsThatProduct() {
        assertThat(search("黑色连帽卫衣多少钱", 5)).contains("CHK-CATALOG-CLOTHING-HOODIE-004");
        assertThat(search("商品编号 CLOTH-SKIRT-007 多少钱", 5)).contains("CHK-CATALOG-CLOTHING-SKIRT-007");
    }

    @Test
    void budgetQuestionMustRecallTheFullPriceTable() {
        // 关键回归：预算类问题不拿到整张表，模型就会用少数单品算出错误结论（曾算出"最低价 229"）
        assertThat(search("1000元预算可以怎么搭配", 5)).contains("CHK-CATALOG-CLOTHING-ALL");
        assertThat(search("我有1000元，最多可以买几件衣服", 5)).contains("CHK-CATALOG-CLOTHING-ALL");
    }

    @Test
    void aggregateQuestionMustRecallTheFullPriceTable() {
        assertThat(search("最便宜的衣服是哪件", 5)).contains("CHK-CATALOG-CLOTHING-ALL");
        assertThat(search("699 以内能买哪些衣服", 5)).contains("CHK-CATALOG-CLOTHING-ALL");
    }

    @Test
    void faqQuestionsStillFindTheirOwnDocuments() {
        assertThat(search("支付成功但订单没生成怎么办", 5)).contains("PAY-001-1");
        assertThat(search("创建支付失败工单需要哪些信息", 5)).contains("PAY-002-1");
        assertThat(search("订单显示 FAILED 是什么意思", 5)).contains("ORDER-001-1");
    }

    @Test
    void resultsCarryScoresAndReason() {
        List<KnowledgeSearchResult> results = repository().search("黑色连帽卫衣多少钱", 3);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(result -> {
            assertThat(result.finalScore()).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
            // 融合口径：能看出这条是"哪一路、什么名次"进来的，排障时才不用猜
            assertThat(result.matchReason()).startsWith("hybrid:").contains("rrf=");
            assertThat(result.chunk().content()).doesNotContain("价格刚好");
        });
        // 冠军两路都命中，且融合分是**绝对量级**（0.7*BM25饱和 + 0.3*余弦），不是"凑出来的 1.0"
        assertThat(results.get(0).finalScore()).isGreaterThan(0.3);
        assertThat(results.get(0).matchReason()).contains("both(");
    }

    // ===== 以下四条是"换个说法"的回归：以前纯 BM25 一条都检不到 =====

    @Test
    void colloquialPriceIsMatchedToTheRealPrice() {
        // 「七百」不是 699：靠中文数字归一（七百→700）+ 数值邻近加成（±2%）才对得上。
        // 注意「一千块左右的外套」是预算+品类问法，冠军是整张价格表（价格表里才有全部外套），
        // 所以这里只断言"检到了 699 那件"与"检到了外套类"，不指定名次。
        assertThat(search("七百块的衣服", 5))
                .as("口语价格必须对上 ¥699 的大衣")
                .contains("CHK-CATALOG-CLOTHING-COAT-009");
        assertThat(search("一千块左右的外套", 5))
                .containsAnyOf("CHK-CATALOG-CLOTHING-ALL", "CHK-CATALOG-CLOTHING-DOWN-023",
                        "CHK-CATALOG-CLOTHING-COAT-009", "CHK-CATALOG-CLOTHING-WINDBREAKER-022");
    }

    @Test
    void synonymOuterwearFindsCoatsAndJackets() {
        // 语料里没有"外套"这个词（只有 大衣/夹克/风衣/羽绒服/开衫），纯字面匹配必挂
        assertThat(search("外套多少钱", 5))
                .containsAnyOf("CHK-CATALOG-CLOTHING-COAT-009", "CHK-CATALOG-CLOTHING-CARDIGAN-017",
                        "CHK-CATALOG-CLOTHING-JACKET-006", "CHK-CATALOG-CLOTHING-WINDBREAKER-022",
                        "CHK-CATALOG-CLOTHING-DOWN-023");
    }

    @Test
    void synonymHoodieAndSkirtFindTheirProducts() {
        assertThat(search("帽衫多少钱", 5))
                .containsAnyOf("CHK-CATALOG-CLOTHING-HOODIE-004", "CHK-CATALOG-CLOTHING-HOODIE-015");
        assertThat(search("裙子有哪些", 5))
                .containsAnyOf("CHK-CATALOG-CLOTHING-SKIRT-007", "CHK-CATALOG-CLOTHING-DRESS-005",
                        "CHK-CATALOG-CLOTHING-SKIRT-026", "CHK-CATALOG-CLOTHING-DRESS-025");
    }

    @Test
    void vectorRouteContributesEvenWithoutExactTerms() {
        // 「想买件厚一点的外套过冬」：没有商品名，靠同义扩展(外套→羽绒服/大衣…) + 向量余弦召回
        List<KnowledgeSearchResult> results = repository().search("想买件厚一点的外套过冬", 5);

        assertThat(results).isNotEmpty();
        assertThat(results).anySatisfy(result -> assertThat(result.matchReason()).contains("vec#"));
        assertThat(results.stream().map(result -> result.chunk().id()).toList())
                .containsAnyOf("CHK-CATALOG-CLOTHING-DOWN-023", "CHK-CATALOG-CLOTHING-COAT-009",
                        "CHK-CATALOG-CLOTHING-COAT-024", "CHK-CATALOG-CLOTHING-WINDBREAKER-022");
    }
}
