package com.example.aiticketassistant.domain.cart;

/** 购物车里的一行：客户 + 商品 + 数量（商品名/价格读取时再补，避免两份事实）。 */
public record CartItem(String customerId, String productId, int quantity) {

    public CartItem {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
    }

    /** 同一商品再加购时合并（而不是插两行）。 */
    public CartItem merge(int additional) {
        return new CartItem(customerId, productId, quantity + Math.max(0, additional));
    }
}
