package com.example.aiticketassistant.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataReturnRequestJpaRepository extends JpaRepository<ReturnRequestJpaEntity, String> {
    Optional<ReturnRequestJpaEntity> findFirstByOrderNoOrderByCreatedAtDesc(String orderNo);

    boolean existsByOrderNo(String orderNo);

    List<ReturnRequestJpaEntity> findTop10ByCustomerIdOrderByIdDesc(String customerId);

    /** 审核台：按状态列申请（新的在前）。 */
    List<ReturnRequestJpaEntity> findByStatusOrderByCreatedAtDesc(String status);
}
