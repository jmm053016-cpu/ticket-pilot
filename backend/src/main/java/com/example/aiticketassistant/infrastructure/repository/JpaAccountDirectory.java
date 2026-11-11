package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.domain.customer.AccountDirectory;
import com.example.aiticketassistant.domain.customer.CustomerAddress;
import com.example.aiticketassistant.domain.customer.CustomerPaymentMethod;
import com.example.aiticketassistant.infrastructure.persistence.jpa.CustomerAddressJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.CustomerPaymentMethodJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataCustomerAddressJpaRepository;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataCustomerPaymentMethodJpaRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 账户查询的 JPA 实现。
 *
 * <p>**故意用 {@code @Component} 而不是 {@code @Repository}**：`@Repository` 会被 Spring 的
 * `PersistenceExceptionTranslationPostProcessor` 包一层 CGLIB 代理，而在这台机器上实测出现
 * "新增的方法在代理实例上执行 → 字段全 null → NPE"（同一个类里老方法却正常）。
 * 账户查询是纯读，不需要异常翻译，绕开代理最省事也最可预测。
 */
@Component
public class JpaAccountDirectory implements AccountDirectory {
    private final SpringDataCustomerAddressJpaRepository addressRepository;
    private final SpringDataCustomerPaymentMethodJpaRepository paymentMethodRepository;

    public JpaAccountDirectory(SpringDataCustomerAddressJpaRepository addressRepository,
                               SpringDataCustomerPaymentMethodJpaRepository paymentMethodRepository) {
        this.addressRepository = addressRepository;
        this.paymentMethodRepository = paymentMethodRepository;
    }

    @Override
    public List<CustomerAddress> findAddresses(String customerId) {
        return addressRepository.findByCustomerIdAndActiveTrueOrderByDefaultAddressDesc(customerId).stream()
                .map(this::toAddress)
                .toList();
    }

    @Override
    public List<CustomerPaymentMethod> findPaymentMethods(String customerId) {
        return paymentMethodRepository.findByCustomerIdAndActiveTrueOrderByDefaultMethodDesc(customerId).stream()
                .map(this::toPaymentMethod)
                .toList();
    }

    private CustomerAddress toAddress(CustomerAddressJpaEntity entity) {
        return new CustomerAddress(entity.getId(), entity.getCustomerId(), entity.getRecipientName(), entity.getPhone(),
                entity.getAddressLine(), entity.isDefaultAddress(), entity.isActive());
    }

    private CustomerPaymentMethod toPaymentMethod(CustomerPaymentMethodJpaEntity entity) {
        return new CustomerPaymentMethod(entity.getId(), entity.getCustomerId(), entity.getMethodType(),
                entity.getDisplayLabel(), entity.isDefaultMethod(), entity.isActive());
    }
}
