package com.example.aiticketassistant.domain.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 待确认订单（两阶段下单的第一阶段产物）。
 *
 * <p>2026-11-05 起是**一单多明细**：{@code items} 至少一行，{@code totalAmount} 等于各行小计之和。
 * 以前是"一单一商品"，于是「买 A 和 B」只能拆成两张卡分别确认（或者干脆丢一件）——
 * 用户反馈的静默丢单就是这么来的，见 §5.3 / §5.4.2。
 */
public class OrderConfirmation {
    private final String id;
    private final String traceId;
    private final String sessionId;
    private final String customerId;
    private final List<OrderConfirmationItem> items;
    private final BigDecimal totalAmount;
    private final String addressId;
    private final String paymentMethodId;
    private OrderConfirmationStatus status;
    private final Instant expiresAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public OrderConfirmation(String id, String traceId, String sessionId, String customerId,
                             List<OrderConfirmationItem> items, String addressId, String paymentMethodId,
                             OrderConfirmationStatus status, Instant expiresAt, Instant createdAt, Instant updatedAt) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("订单确认至少要有 1 行明细");
        }
        this.id = require(id, "confirmation id");
        this.traceId = require(traceId, "trace id");
        this.sessionId = require(sessionId, "session id");
        this.customerId = require(customerId, "customer id");
        this.items = List.copyOf(items);
        this.totalAmount = this.items.stream()
                .map(OrderConfirmationItem::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.addressId = require(addressId, "address id");
        this.paymentMethodId = require(paymentMethodId, "payment method id");
        this.status = status == null ? OrderConfirmationStatus.PENDING : status;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    public boolean expired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void confirm() {
        status = OrderConfirmationStatus.CONFIRMED;
        updatedAt = Instant.now();
    }

    public void cancel() {
        status = OrderConfirmationStatus.CANCELLED;
        updatedAt = Instant.now();
    }

    public void fail() {
        status = OrderConfirmationStatus.FAILED;
        updatedAt = Instant.now();
    }

    public void expire() {
        status = OrderConfirmationStatus.EXPIRED;
        updatedAt = Instant.now();
    }

    private String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String id() { return id; }
    public String traceId() { return traceId; }
    public String sessionId() { return sessionId; }
    public String customerId() { return customerId; }
    public List<OrderConfirmationItem> items() { return items; }
    /** 商品总件数（各行数量之和）。 */
    public int totalQuantity() { return items.stream().mapToInt(OrderConfirmationItem::quantity).sum(); }
    public BigDecimal totalAmount() { return totalAmount; }
    public String addressId() { return addressId; }
    public String paymentMethodId() { return paymentMethodId; }
    public OrderConfirmationStatus status() { return status; }
    public Instant expiresAt() { return expiresAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
