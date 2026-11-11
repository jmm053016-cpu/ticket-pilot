package com.example.aiticketassistant.domain.cart;

import java.util.List;

/** 购物车仓储端口（一个客户一辆车，商品维度唯一）。 */
public interface CartRepository {
    List<CartItem> findByCustomer(String customerId);

    /** 已存在则累加数量，否则插入；返回写入后的行。 */
    CartItem upsert(CartItem item);

    /** 清空某客户的购物车（下单成功后调用）。 */
    void clear(String customerId);

    void remove(String customerId, String productId);
}
