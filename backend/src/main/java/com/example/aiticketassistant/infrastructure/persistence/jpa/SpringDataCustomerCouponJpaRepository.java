package com.example.aiticketassistant.infrastructure.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataCustomerCouponJpaRepository extends JpaRepository<CustomerCouponJpaEntity, String> {
    List<CustomerCouponJpaEntity> findByCustomerIdOrderByAmountOffDesc(String customerId);
}
