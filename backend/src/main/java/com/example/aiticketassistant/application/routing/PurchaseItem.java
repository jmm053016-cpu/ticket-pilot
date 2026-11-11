package com.example.aiticketassistant.application.routing;

/**
 * 一句话里识别出的一个待购买商品（`QuestionSignals.purchaseItems` 的产物）。
 *
 * @param name      句子里出现的商品说法（原样保留，用于回显/确认卡片）
 * @param productId 受信任的商品 ID（只能来自 {@code PRODUCT_NAME_TO_ID} 或句中的商品编号）
 * @param quantity  该商品的购买数量（至少 1）
 */
public record PurchaseItem(String name, String productId, int quantity) {}
