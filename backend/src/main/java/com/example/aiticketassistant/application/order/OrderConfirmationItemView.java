package com.example.aiticketassistant.application.order;

import java.math.BigDecimal;

/**
 * 确认卡片上的一行商品。
 *
 * @param lineAmount 行小计（= unitPrice × quantity），派生值，不给前端算
 */
public record OrderConfirmationItemView(
        String productId,
        String productName,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineAmount) {}
