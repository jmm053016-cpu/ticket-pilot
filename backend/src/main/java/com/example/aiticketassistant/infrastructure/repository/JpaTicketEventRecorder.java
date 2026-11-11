package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.application.ticket.TicketEventRecorder;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataTicketEventJpaRepository;
import com.example.aiticketassistant.infrastructure.persistence.jpa.TicketEventJpaEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把工单状态变更追加成 {@code ticket_events} 的一行，形成可回放的工单时间线。
 *
 * <p>写失败只 warn，不抛——工单本身已经落库成功了，事件缺失不能反向失败。
 */
@Component
public class JpaTicketEventRecorder implements TicketEventRecorder {
    private static final Logger log = LoggerFactory.getLogger(JpaTicketEventRecorder.class);

    private final SpringDataTicketEventJpaRepository repository;
    private final ObjectMapper objectMapper;

    public JpaTicketEventRecorder(SpringDataTicketEventJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(String ticketId, String eventType, Map<String, Object> payload) {
        try {
            TicketEventJpaEntity entity = new TicketEventJpaEntity();
            entity.setTicketId(ticketId);
            entity.setEventType(eventType);
            entity.setPayloadJson(toJson(payload));
            entity.setCreatedAt(Instant.now());
            repository.save(entity);
        } catch (RuntimeException ex) {
            log.warn("工单事件落库失败 ticketId={} eventType={}，已忽略", ticketId, eventType, ex);
        }
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("工单事件 payload 序列化失败，写入 null", ex);
            return null;
        }
    }
}
