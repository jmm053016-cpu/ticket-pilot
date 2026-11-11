package com.example.aiticketassistant.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataAssistantMessageJpaRepository extends JpaRepository<AssistantMessageJpaEntity, Long> {
    /** 取某会话最近 N 条消息（倒序，调用方自行反转成时间正序）。 */
    List<AssistantMessageJpaEntity> findTop40BySessionIdOrderByIdDesc(String sessionId);

    /** 会话消息总数（会话侧栏展示用）。 */
    long countBySessionId(String sessionId);

    /** 删除某会话的全部消息（删除会话时级联清理；表上没有外键，得自己删）。 */
    long deleteBySessionId(String sessionId);

    /** 会话里第一条某个角色的消息（用于摘取 preview）。 */
    Optional<AssistantMessageJpaEntity> findFirstBySessionIdAndRoleOrderByIdAsc(String sessionId, String role);
}