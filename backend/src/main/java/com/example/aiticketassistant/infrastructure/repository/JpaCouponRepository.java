package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.domain.customer.CouponRepository;
import com.example.aiticketassistant.domain.customer.CustomerCoupon;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataCustomerCouponJpaRepository;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaCouponRepository implements CouponRepository {
    private final SpringDataCustomerCouponJpaRepository repository;

    public JpaCouponRepository(SpringDataCustomerCouponJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerCoupon> findByCustomer(String customerId) {
        return repository.findByCustomerIdOrderByAmountOffDesc(customerId).stream()
                .map(entity -> new CustomerCoupon(
                        entity.getId(), entity.getCustomerId(), entity.getCode(), entity.getTitle(),
                        entity.getAmountOff(), entity.getMinSpend(), entity.getExpiresAt(), entity.getStatus()))
                .toList();
    }
}
