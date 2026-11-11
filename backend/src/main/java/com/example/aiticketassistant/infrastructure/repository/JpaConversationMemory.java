package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.application.conversation.ConversationMemory;
import com.example.aiticketassistant.application.conversation.ConversationTurn;
import com.example.aiticketassistant.application.conversation.SessionSummary;
import com.example.aiticketassistant.domain.shared.DomainException;
import com.example.aiticketassistant.domain.shared.NotFoundException;
import com.example.aiticketassistant.infrastructure.persistence.jpa.AssistantMessageJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.AssistantSessionJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataAssistantMessageJpaRepository;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataAssistantSessionJpaRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 多轮会话记忆的持久化实现：{@code assistant_sessions} + {@code assistant_messages}。
 *
 * <p>写入策略：一轮结束时一次性写入「用户问 + 助手答」两条消息，因此历史里不会出现半轮。
 * 读写失败都只 warn，不抛——记忆是增强能力，不能反过来让回答失败。
 */
@Component
public class JpaConversationMemory implements ConversationMemory {
    private static final Logger log = LoggerFactory.getLogger(JpaConversationMemory.class);
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    /** 单次最多回看多少条消息（Repository 的 fetch 上限，够 maxTurns=20 用）。 */
    private static final int FETCH_LIMIT = 40;

    private final SpringDataAssistantSessionJpaRepository sessionRepository;
    private final SpringDataAssistantMessageJpaRepository messageRepository;
    private final TransactionTemplate transactionTemplate;

    public JpaConversationMemory(SpringDataAssistantSessionJpaRepository sessionRepository,
                                 SpringDataAssistantMessageJpaRepository messageRepository,
                                 PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<ConversationTurn> recentTurns(String sessionId, int maxTurns) {
        if (sessionId == null || sessionId.isBlank() || maxTurns <= 0) {
            return List.of();
        }
        try {
            List<AssistantMessageJpaEntity> desc =
                    messageRepository.findTop40BySessionIdOrderByIdDesc(sessionId);
            if (desc.isEmpty()) {
                return List.of();
            }
            List<AssistantMessageJpaEntity> asc = new ArrayList<>(desc);
            Collections.reverse(asc);

            List<ConversationTurn> turns = new ArrayList<>();
            String pendingUser = null;
            for (AssistantMessageJpaEntity message : asc) {
                if (ROLE_USER.equals(message.getRole())) {
                    pendingUser = message.getContent();
                } else if (ROLE_ASSISTANT.equals(message.getRole()) && pendingUser != null) {
                    turns.add(new ConversationTurn(pendingUser, message.getContent()));
                    pendingUser = null;
                }
            }
            if (turns.size() > maxTurns) {
                turns = new ArrayList<>(turns.subList(turns.size() - maxTurns, turns.size()));
            }
            return turns;
        } catch (RuntimeException ex) {
            log.warn("读取会话历史失败 sessionId={}，按无历史处理", sessionId, ex);
            return List.of();
        }
    }

    @Override
    public void recordTurn(String sessionId, String customerId, String userMessage, String assistantAnswer) {
        if (sessionId == null || sessionId.isBlank() || userMessage == null || userMessage.isBlank()
                || assistantAnswer == null || assistantAnswer.isBlank()) {
            return;
        }
        try {
            Instant now = Instant.now();
            // 会话行 + 两条消息要么都写、要么都不写（用显式事务，见 deleteSession 上的说明）
            transactionTemplate.executeWithoutResult(status -> {
                AssistantSessionJpaEntity session = sessionRepository.findById(sessionId).orElseGet(() -> {
                    AssistantSessionJpaEntity fresh = new AssistantSessionJpaEntity();
                    fresh.setId(sessionId);
                    fresh.setCreatedAt(now);
                    return fresh;
                });
                session.setCustomerId(customerId);
                session.setUpdatedAt(now);
                sessionRepository.save(session);

                messageRepository.save(message(sessionId, ROLE_USER, userMessage, now));
                messageRepository.save(message(sessionId, ROLE_ASSISTANT, assistantAnswer, now));
            });
        } catch (RuntimeException ex) {
            log.warn("写入会话历史失败 sessionId={}，已忽略", sessionId, ex);
        }
    }

    /**
     * 校验会话归属（2026-11-09 补）：修掉"只要知道 sessionId 就能读别人历史"。
     *
     * <p>故意不包事务：单条读，包事务反而多一次连接/会话切换，没有收益。
     * 越权与不存在返回**同一个** 404，避免把"会话是否存在"当探针。
     */
    @Override
    public void requireOwnership(String sessionId, String customerId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new NotFoundException("会话不存在");
        }
        AssistantSessionJpaEntity session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("会话不存在: " + sessionId));
        if (customerId != null && !customerId.isBlank() && !customerId.equals(session.getCustomerId())) {
            throw new NotFoundException("会话不存在: " + sessionId);
        }
    }

    /**
     * 删除会话：**用显式 TransactionTemplate，而不是 {@code @Transactional}**。
     *
     * <p>踩过的坑（2026-11-05）：本类里新加的这个方法经 CGLIB 代理调用时，走的是"在代理实例上执行父类方法"
     * 那条路径，而 CGLIB 代理实例的字段是 null（Objenesis 建实例不跑构造器）→ 直接 NPE
     * "sessionRepository is null"；同类的 {@code recordTurn} 却正常。与其和 AOP 细节纠缠，
     * 这里显式开事务：语义等价（两步在同一个事务里）、可读、可测。
     */
    @Override
    public boolean deleteSession(String sessionId, String customerId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Boolean deleted = transactionTemplate.execute(status -> {
            AssistantSessionJpaEntity session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
                return false;
            }
            // 归属校验：不能让 A 客户删掉 B 客户的会话（前端只传 sessionId，不给它越权的机会）
            if (customerId != null && !customerId.isBlank() && !customerId.equals(session.getCustomerId())) {
                throw new DomainException("会话不属于当前客户，不能删除");
            }
            // 表上没有外键，消息要自己清；两步在同一个事务里，不会留下孤儿消息
            messageRepository.deleteBySessionId(sessionId);
            sessionRepository.deleteById(sessionId);
            return true;
        });
        return Boolean.TRUE.equals(deleted);
    }

    @Override
    public List<SessionSummary> recentSessions(String customerId, int limit) {
        if (customerId == null || customerId.isBlank() || limit <= 0) {
            return List.of();
        }
        try {
            // 注意：每条会话额外两次查询（preview + 计数）。会话数上限 20，可接受；
            // 若要上量应改成一条聚合 SQL，这里优先保持可读性。
            return sessionRepository.findTop20ByCustomerIdOrderByUpdatedAtDesc(customerId).stream()
                    .limit(limit)
                    .map(this::toSummary)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("列出会话失败 customerId={}，返回空列表", customerId, ex);
            return List.of();
        }
    }

    private SessionSummary toSummary(AssistantSessionJpaEntity session) {
        String preview = messageRepository
                .findFirstBySessionIdAndRoleOrderByIdAsc(session.getId(), ROLE_USER)
                .map(message -> truncate(message.getContent(), 40))
                .orElse("（空会话）");
        long messageCount = messageRepository.countBySessionId(session.getId());
        return new SessionSummary(session.getId(), preview, messageCount, session.getCreatedAt(), session.getUpdatedAt());
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
    private AssistantMessageJpaEntity message(String sessionId, String role, String content, Instant createdAt) {
        AssistantMessageJpaEntity entity = new AssistantMessageJpaEntity();
        entity.setSessionId(sessionId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(createdAt);
        return entity;
    }
}
