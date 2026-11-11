package com.example.aiticketassistant.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataCartItemJpaRepository extends JpaRepository<CartItemJpaEntity, Long> {
    List<CartItemJpaEntity> findByCustomerIdOrderByIdAsc(String customerId);

    Optional<CartItemJpaEntity> findByCustomerIdAndProductId(String customerId, String productId);
}
