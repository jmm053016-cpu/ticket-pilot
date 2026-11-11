package com.example.aiticketassistant.domain.customer;

import java.math.BigDecimal;
import java.time.Instant;

/** 客户优惠券（演示数据）。`usable(now)` 把"过期/已用"的判断收在领域里，服务层不再各写一遍。 */
public record CustomerCoupon(String id, String customerId, String code, String title,
                             BigDecimal amountOff, BigDecimal minSpend, Instant expiresAt, String status) {

    public boolean usable(Instant now) {
        return "UNUSED".equals(status) && expiresAt != null && expiresAt.isAfter(now);
    }
}
