package com.example.aiticketassistant.application.catalog;

import com.example.aiticketassistant.domain.catalog.CatalogItem;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunkSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 从知识库片段里解析出**确定的商品价格表**。
 *
 * <p>为什么不让模型读检索结果自己算：实测它会给出自洽但错误的合计（同一问题不同次跑出 934 / 954 / 964，
 * 真实值是 1144），而且同一段回答里结论与明细表互相矛盾。价格这种硬数据必须由代码解析、由代码累加。
 *
 * <p>数据来源是 {@link KnowledgeChunkSource}（全量片段），**不依赖检索命中** ——
 * 否则"检索少召回一个商品"就会让测算结论出错。
 */
@Service
public class PriceCatalogService {
    /** 单品片段格式：商品编号 CLOTH-COAT-009，商品名称 羊毛混纺大衣，服装商品，价格 ¥699.00。 */
    private static final Pattern SINGLE_ITEM_PATTERN = Pattern.compile(
            "商品编号\\s*(CLOTH-[A-Z]+-\\d{3})[，,]\\s*商品名称\\s*([^，,]+)[，,][^。]*?¥\\s*([0-9]+(?:\\.[0-9]+)?)");
    /** 汇总片段格式：基础纯棉白色T恤 CLOTH-TEE-001 ¥99.00；蓝色牛津纺衬衫 CLOTH-SHIRT-002 ¥199.00； */
    private static final Pattern SUMMARY_ITEM_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z0-9]+)\\s+(CLOTH-[A-Z]+-\\d{3})\\s+¥\\s*([0-9]+(?:\\.[0-9]+)?)");

    private final KnowledgeChunkSource chunkSource;

    public PriceCatalogService(KnowledgeChunkSource chunkSource) {
        this.chunkSource = chunkSource;
    }

    /** @return 去重后按价格升序的商品列表；知识库里没有商品时返回空列表。 */
    public List<CatalogItem> items() {
        Map<String, CatalogItem> byProductId = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunkSource.all()) {
            String content = chunk.content() == null ? "" : chunk.content();
            collect(byProductId, SINGLE_ITEM_PATTERN.matcher(content), 1, 2, 3);
            collect(byProductId, SUMMARY_ITEM_PATTERN.matcher(content), 2, 1, 3);
        }
        List<CatalogItem> items = new ArrayList<>(byProductId.values());
        items.sort(Comparator.comparingLong(CatalogItem::priceCents));
        return List.copyOf(items);
    }

    /** 两种片段格式的字段顺序不同，用下标指定 id / name / price 所在的捕获组。 */
    private void collect(Map<String, CatalogItem> target, Matcher matcher, int idGroup, int nameGroup, int priceGroup) {
        while (matcher.find()) {
            String productId = matcher.group(idGroup).trim();
            String name = matcher.group(nameGroup).trim();
            long cents = toCents(matcher.group(priceGroup));
            if (cents <= 0) {
                continue;
            }
            target.putIfAbsent(productId, new CatalogItem(productId, name, cents));
        }
    }

    private long toCents(String price) {
        try {
            return Math.round(Double.parseDouble(price) * 100);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
