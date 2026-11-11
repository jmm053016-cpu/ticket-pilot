package com.example.aiticketassistant.application.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 商品目录与关键词表的**一致性检查**（防止"加了商品但下单/检索跟不上"）。
 *
 * <p>商品数据有三处副本，任何一处漏改都会出现难查的线上症状：
 * <table>
 *   <tr><th>副本</th><th>漏改的症状</th></tr>
 *   <tr><td>{@code products} 表（迁移 INSERT）</td><td>知识库查得到，但下单报"商品不存在 / 无库存"</td></tr>
 *   <tr><td>汇总价格表片段 {@code CHK-CATALOG-CLOTHING-ALL}</td><td>预算测算（PriceCatalogService）里没有这件商品</td></tr>
 *   <tr><td>{@code QuestionSignals} 的商品名→productId 表</td><td>"我要买一件XX" 无法下单，只能反问</td></tr>
 * </table>
 *
 * <p>这份测试直接读迁移 SQL，所以**新增商品时它一定会提醒你去补另外两处**。
 */
class SeededProductCatalogConsistencyTest {
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    /** products 的 INSERT 行：('CLOTH-XXX-001', '商品名', 99.00, true, …) */
    private static final Pattern PRODUCT_INSERT = Pattern.compile(
            "\\('(CLOTH-[A-Z]+-\\d{3})',\\s*'([^']+)',\\s*[0-9.]+\\s*,\\s*(?:true|TRUE)");
    /** 汇总价格表里的条目：商品名 CLOTH-XXX-001 ¥99.00 */
    private static final Pattern SUMMARY_ENTRY = Pattern.compile("(CLOTH-[A-Z]+-\\d{3})\\s*¥");

    @Test
    void everySeededProductCanBeResolvedFromAProductName() {
        Map<String, String> seeded = seededProducts();
        assertThat(seeded).as("迁移里应当有商品种子").isNotEmpty();

        seeded.forEach((productId, name) -> assertThat(QuestionSignals.productIdIn("我要买一件" + name))
                .as("「我要买一件%s」必须解析到 %s（顺序：具体商品名要排在通用品类词之前）", name, productId)
                .isEqualTo(productId));
    }

    @Test
    void summaryPriceTableCoversExactlyTheSeededProducts() {
        Set<String> seeded = seededProducts().keySet();
        Set<String> inSummary = new LinkedHashSet<>();
        Matcher matcher = SUMMARY_ENTRY.matcher(allMigrationSql());
        while (matcher.find()) {
            inSummary.add(matcher.group(1));
        }

        assertThat(inSummary)
                .as("汇总价格表（预算测算的数据源）必须与 products 表一一对应")
                .containsExactlyInAnyOrderElementsOf(seeded);
    }

    @Test
    void purchaseWordingStillWorksForEverySeededProduct() {
        seededProducts().forEach((productId, name) ->
                assertThat(QuestionSignals.purchaseProductId("我要买一件" + name))
                        .as("「我要买一件%s」必须能直接下单", name)
                        .isEqualTo(productId));
    }

    /** productId → 商品名，按迁移出现顺序（同 id 只保留第一次）。 */
    private static Map<String, String> seededProducts() {
        Map<String, String> products = new LinkedHashMap<>();
        Matcher matcher = PRODUCT_INSERT.matcher(allMigrationSql());
        while (matcher.find()) {
            products.putIfAbsent(matcher.group(1), matcher.group(2));
        }
        return products;
    }

    private static String allMigrationSql() {
        StringBuilder sql = new StringBuilder();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            List<Path> ordered = files.filter(path -> path.toString().endsWith(".sql")).sorted().toList();
            for (Path file : ordered) {
                sql.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return sql.toString();
    }
}
