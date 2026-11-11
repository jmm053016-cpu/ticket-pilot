package com.example.aiticketassistant.domain.catalog;

public record ProductInventory(String productId, int stockOnHand, int reservedStock) {
    public int availableStock() {
        return stockOnHand - reservedStock;
    }

    public ProductInventory reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (availableStock() < quantity) {
            throw new IllegalArgumentException("Insufficient stock for product: " + productId);
        }
        return new ProductInventory(productId, stockOnHand, reservedStock + quantity);
    }

    /** 释放已预留的库存（取消订单时回滚）。下限 0，避免历史脏数据把预留量压成负数。 */
    public ProductInventory release(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        return new ProductInventory(productId, stockOnHand, Math.max(0, reservedStock - quantity));
    }
}
