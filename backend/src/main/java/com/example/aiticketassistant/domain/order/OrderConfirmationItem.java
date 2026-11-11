package com.example.aiticketassistant.domain.order;

import java.math.BigDecimal;

/**
 * 待确认订单里的一行明细（商品 + 数量 + 单价）。
 *
 * <p>为什么不存"行小计"：{@code quantity * unitPrice} 是派生值，存两份迟早对不上
 * （历史上就因为"汇总表 + 明细"两处数据打架踩过坑）。金额只在**读取时**算出来。
 */
public record OrderConfirmationItem(String productId, String productName, int quantity, BigDecimal unitPrice) {

    public OrderConfirmationItem {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
    }

    /** 行小计。 */
    public BigDecimal lineAmount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
