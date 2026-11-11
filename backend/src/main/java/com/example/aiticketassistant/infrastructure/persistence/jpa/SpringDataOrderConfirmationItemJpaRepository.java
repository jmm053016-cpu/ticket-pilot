package com.example.aiticketassistant.infrastructure.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataOrderConfirmationItemJpaRepository
        extends JpaRepository<OrderConfirmationItemJpaEntity, Long> {

    /** 按插入顺序返回明细（下单时行序 = 用户说的顺序）。 */
    List<OrderConfirmationItemJpaEntity> findByConfirmationIdOrderByIdAsc(String confirmationId);
}
