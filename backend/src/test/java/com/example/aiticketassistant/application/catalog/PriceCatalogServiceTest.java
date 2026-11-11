package com.example.aiticketassistant.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.domain.catalog.CatalogItem;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunkSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 价格表解析（预算测算的数据来源）。
 *
 * <p>直接拿**迁移里的真实片段**当语料：`PriceCatalogService` 有两种格式要认
 * （单品片段「商品编号 X，商品名称 Y，… 价格 ¥Z」、汇总片段「Y X ¥Z；」），
 * 任一格式写坏了，预算测算就会少算商品 —— 而少算商品的结论看起来仍然"自洽"。
 */
class PriceCatalogServiceTest {
    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Pattern SUMMARY_UPDATE = Pattern.compile(
            "UPDATE knowledge_chunks\\s+SET content = '([^']+)'\\s+WHERE id = 'CHK-CATALOG-CLOTHING-ALL'");
    private static final Pattern SINGLE_CHUNK = Pattern.compile(
            "'(CHK-CATALOG-CLOTHING-[A-Z]+-\\d{3})', '[^']+', '([^']+)', 'CATALOG', '[^']+', '([^']+)'");

    @Test
    void summaryChunkAloneYieldsTheWholeCatalog() {
        // 汇总是"整张价格表"的唯一副本：只靠它就该解析出全部 60 件（V10 后）
        List<CatalogItem> items = items(List.of(chunk("CHK-CATALOG-CLOTHING-ALL", "服装商品目录：完整价格表", summaryContent())));

        assertThat(items).hasSize(60);
        assertThat(items.get(0).productId()).isEqualTo("CLOTH-TEE-001");
        assertThat(items.get(0).priceCents()).isEqualTo(9900);
        assertThat(items.get(items.size() - 1).productId()).isEqualTo("CLOTH-COAT-024");
        assertThat(items.get(items.size() - 1).priceCents()).isEqualTo(129900);
    }

    @Test
    void singleItemChunksYieldTheSameCatalogAsTheSummary() {
        List<CatalogItem> fromSingles = items(singleItemChunks());
        List<CatalogItem> fromSummary = items(List.of(chunk("CHK-CATALOG-CLOTHING-ALL", "服装商品目录：完整价格表", summaryContent())));

        assertThat(fromSingles).isNotEmpty();
        assertThat(fromSingles).extracting(CatalogItem::productId)
                .containsExactlyInAnyOrderElementsOf(fromSummary.stream().map(CatalogItem::productId).toList());
        assertThat(fromSingles).extracting(CatalogItem::priceCents)
                .containsExactlyInAnyOrderElementsOf(fromSummary.stream().map(CatalogItem::priceCents).toList());
    }

    @Test
    void duplicateCopiesAreDedupedByProductId() {
        // 单品片段 + 汇总片段同时命中同一件商品时只能算一次，否则预算会翻倍
        List<KnowledgeChunk> both = new java.util.ArrayList<>(singleItemChunks());
        both.add(chunk("CHK-CATALOG-CLOTHING-ALL", "服装商品目录：完整价格表", summaryContent()));

        assertThat(items(both)).hasSize(60);
    }

    private static List<CatalogItem> items(List<KnowledgeChunk> chunks) {
        KnowledgeChunkSource source = () -> chunks;
        return new PriceCatalogService(source).items();
    }

    private static KnowledgeChunk chunk(String id, String title, String content) {
        return new KnowledgeChunk(id, "DOC-CATALOG-CLOTHING", title, "CATALOG", "internal://catalog/clothing", content);
    }

    /** 迁移里的全部单品片段（V5 + V8 + V10）——验证单品格式也能解析。 */
    private static List<KnowledgeChunk> singleItemChunks() {
        List<KnowledgeChunk> chunks = new java.util.ArrayList<>();
        Matcher matcher = SINGLE_CHUNK.matcher(allMigrationSql());
        while (matcher.find()) {
            chunks.add(chunk(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        return chunks;
    }

    /** 汇总片段的**最终内容**：V8 与 V10 各 UPDATE 过一次，取最后一次（迁移按版本号顺序执行）。 */
    private static String summaryContent() {
        Matcher matcher = SUMMARY_UPDATE.matcher(allMigrationSql());
        String content = null;
        while (matcher.find()) {
            content = matcher.group(1);
        }
        assertThat(content).as("迁移里应当有汇总片段的 UPDATE").isNotNull();
        return content;
    }

    /**
     * 按**迁移版本号数值**拼接（不能按文件名字典序：V10 排在 V8 前面，那样"最后一次 UPDATE"会取到旧数据）。
     */
    private static String allMigrationSql() {
        StringBuilder sql = new StringBuilder();
        try (var files = Files.list(MIGRATION_DIR)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).sorted(
                    java.util.Comparator.comparingInt(PriceCatalogServiceTest::migrationVersion)).toList()) {
                sql.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return sql.toString();
    }

    private static int migrationVersion(Path path) {
        Matcher matcher = Pattern.compile("V(\\d+)__").matcher(path.getFileName().toString());
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }
}
