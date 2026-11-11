package com.example.aiticketassistant.support;

import com.example.aiticketassistant.domain.catalog.Product;
import com.example.aiticketassistant.domain.catalog.ProductInventory;
import com.example.aiticketassistant.domain.catalog.ProductRepository;
import com.example.aiticketassistant.domain.order.Order;
import com.example.aiticketassistant.domain.order.OrderRepository;
import java.util.List;
import java.util.Optional;

/**
 * 测试用的空仓储（2026-11-11）。
 *
 * <p>用途：{@code IntentAgent} 现在依赖"品类候选"和"最近一单"两个查询，
 * 大多数用例希望它们**什么都不返回**（保持原有行为），用这两个实现把噪音降到最低。
 */
public final class FakeRepositories {

    private FakeRepositories() {}

    /** 空目录：任何"品类候选"查询都返回空（不会打断正常下单路径）。 */
    public static ProductRepository emptyProducts() {
        return new ProductRepository() {
            @Override
            public Optional<Product> findById(String id) {
                return Optional.empty();
            }

            @Override
            public List<Product> findActiveProducts() {
                return List.of();
            }

            @Override
            public Optional<ProductInventory> findInventory(String productId) {
                return Optional.empty();
            }

            @Override
            public ProductInventory saveInventory(ProductInventory inventory) {
                return inventory;
            }
        };
    }

    /** 空订单：缺订单号时不自动补（照常要求用户提供）。 */
    public static OrderRepository emptyOrders() {
        return new OrderRepository() {
            @Override
            public Optional<Order> findByOrderNo(String orderNo) {
                return Optional.empty();
            }

            @Override
            public Optional<Order> findLatestByCustomerId(String customerId) {
                return Optional.empty();
            }

            @Override
            public List<Order> findRecentByCustomerId(String customerId, int limit) {
                return List.of();
            }

            @Override
            public Order save(Order order) {
                return order;
            }
        };
    }
}
