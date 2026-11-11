package com.example.aiticketassistant.domain.customer;

import java.util.List;

/**
 * 账户查询端口：列出客户全部有效地址 / 支付方式。
 *
 * <p>为什么不挂在 {@link CustomerTrustRepository} 上：那个端口服务的是**下单校验**（按 id 取单个、必须有默认项）。
 * 账户查询是"列全部"的读模型，混进去会让下单路径依赖用不上的方法。
 */
public interface AccountDirectory {
    List<CustomerAddress> findAddresses(String customerId);

    List<CustomerPaymentMethod> findPaymentMethods(String customerId);
}
