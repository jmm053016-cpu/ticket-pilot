package com.example.aiticketassistant.domain.catalog;

/**
 * 商品价格条目（从知识库的服装目录片段解析出来）。
 *
 * <p>价格用「分」存整数，避免浮点误差 —— 预算测算里 99.00 + 169.00 这类加法必须精确。
 */
public record CatalogItem(String productId, String name, long priceCents) {

    public String priceLabel() {
        return "¥" + String.format("%.2f", priceCents / 100.0);
    }
}
