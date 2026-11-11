package com.example.aiticketassistant.application.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 待确认订单的对外视图（SSE 的 {@code order_confirmation_required} 与确认卡片用）。
 *
 * <p>2026-11-05 起**一单多明细**：{@code items} 至少一行，{@code totalAmount} 是各行小计之和。
 * 前端据此渲染"一张卡、多行商品"，用户确认一次即整单下单。
 */
public record OrderConfirmationView(
        String confirmationId,
        String sessionId,
        String customerId,
        String message,
        List<OrderConfirmationItemView> items,
        int totalQuantity,
        BigDecimal totalAmount,
        Map<String, Object> address,
        Map<String, Object> paymentMethod,
        Instant expiresAt) {}
