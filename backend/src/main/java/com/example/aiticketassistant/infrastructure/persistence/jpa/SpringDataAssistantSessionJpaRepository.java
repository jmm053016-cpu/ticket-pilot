package com.example.aiticketassistant.infrastructure.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataAssistantSessionJpaRepository extends JpaRepository<AssistantSessionJpaEntity, String> {
    /** 某客户最近的会话，按最近活跃时间倒序（会话侧栏用）。 */
    List<AssistantSessionJpaEntity> findTop20ByCustomerIdOrderByUpdatedAtDesc(String customerId);
}