package com.example.aiticketassistant.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 对应 V1 迁移里的 {@code workflow_traces}：主键是 traceId 本身（VARCHAR(128)），无自增。
 */
@Entity
@Table(name = "workflow_traces")
public class WorkflowTraceJpaEntity {
    @Id
    @Column(length = 128)
    private String id;
    @Column(nullable = false, length = 64)
    private String sessionId;
    @Column(nullable = false, length = 64)
    private String customerId;
    @Column(nullable = false, length = 32)
    private String status;
    @Column(nullable = false)
    private Instant startedAt;
    private Instant completedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
