package com.example.aiticketassistant.domain.customer;

import java.util.List;

/** 优惠券读取端口。写操作不在对话侧（发券属于运营系统）。 */
public interface CouponRepository {
    List<CustomerCoupon> findByCustomer(String customerId);
}
