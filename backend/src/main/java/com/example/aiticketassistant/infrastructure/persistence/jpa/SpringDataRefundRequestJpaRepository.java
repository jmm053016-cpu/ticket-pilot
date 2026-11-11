package com.example.aiticketassistant.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataRefundRequestJpaRepository extends JpaRepository<RefundRequestJpaEntity, String> {
    boolean existsByOrderNo(String orderNo);

    List<RefundRequestJpaEntity> findTop10ByCustomerIdOrderByIdDesc(String customerId);

    Optional<RefundRequestJpaEntity> findFirstByOrderNoOrderByCreatedAtDesc(String orderNo);

    /** 审核台：按状态列申请（新的在前）。 */
    List<RefundRequestJpaEntity> findByStatusOrderByCreatedAtDesc(String status);
}
