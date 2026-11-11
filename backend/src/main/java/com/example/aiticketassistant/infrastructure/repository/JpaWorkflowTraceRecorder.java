package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.application.workflow.WorkflowStep;
import com.example.aiticketassistant.application.workflow.WorkflowTrace;
import com.example.aiticketassistant.application.workflow.WorkflowTraceRecorder;
import com.example.aiticketassistant.application.workflow.WorkflowTraceStatus;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataWorkflowTraceJpaRepository;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataWorkflowTraceStepJpaRepository;
import com.example.aiticketassistant.infrastructure.persistence.jpa.WorkflowTraceJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.WorkflowTraceStepJpaEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把一次工作流的 trace + 全部步骤落库，让 AI 的决策链可以在请求结束后被回放与审计。
 *
 * <p>与 {@link JpaToolInvocationRecorder} 的分工：那里记"调了哪个工具、参数与结果"，
 * 这里记"整条链路按什么顺序走了哪些步骤、每步耗时多少"，两边用同一个 traceId 关联。
 *
 * <p>写失败只 warn，不抛——回答已经推给用户了，不能让审计写入回流成一次 500。
 */
@Component
public class JpaWorkflowTraceRecorder implements WorkflowTraceRecorder {
    private static final Logger log = LoggerFactory.getLogger(JpaWorkflowTraceRecorder.class);

    private final SpringDataWorkflowTraceJpaRepository traceRepository;
    private final SpringDataWorkflowTraceStepJpaRepository stepRepository;
    private final ObjectMapper objectMapper;

    public JpaWorkflowTraceRecorder(SpringDataWorkflowTraceJpaRepository traceRepository,
                                    SpringDataWorkflowTraceStepJpaRepository stepRepository,
                                    ObjectMapper objectMapper) {
        this.traceRepository = traceRepository;
        this.stepRepository = stepRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void record(WorkflowContext context, WorkflowTraceStatus status, Instant completedAt) {
        try {
            WorkflowTrace trace = context.trace();
            traceRepository.save(toTraceEntity(context, trace, status, completedAt));
            List<WorkflowTraceStepJpaEntity> steps = trace.steps().stream()
                    .map(step -> toStepEntity(trace.traceId(), step))
                    .toList();
            if (!steps.isEmpty()) {
                stepRepository.saveAll(steps);
            }
            log.debug("workflow trace 已落库 traceId={} status={} steps={}", trace.traceId(), status, steps.size());
        } catch (RuntimeException ex) {
            log.warn("workflow trace 落库失败 traceId={}，已忽略以免影响已返回的回答", context.trace().traceId(), ex);
        }
    }

    private WorkflowTraceJpaEntity toTraceEntity(WorkflowContext context, WorkflowTrace trace,
                                                 WorkflowTraceStatus status, Instant completedAt) {
        WorkflowTraceJpaEntity entity = new WorkflowTraceJpaEntity();
        entity.setId(trace.traceId());
        entity.setSessionId(context.sessionId());
        entity.setCustomerId(context.customerId());
        entity.setStatus(status.name());
        entity.setStartedAt(trace.startedAt());
        entity.setCompletedAt(completedAt);
        return entity;
    }

    private WorkflowTraceStepJpaEntity toStepEntity(String traceId, WorkflowStep step) {
        WorkflowTraceStepJpaEntity entity = new WorkflowTraceStepJpaEntity();
        entity.setTraceId(traceId);
        entity.setStepName(truncate(step.name(), 128));
        entity.setStepKind(truncate(step.kind(), 64));
        entity.setStatus(step.status().name());
        entity.setInputJson(toJson(step.input()));
        entity.setOutputJson(toJson(step.output()));
        entity.setStartedAt(step.startedAt());
        entity.setCompletedAt(step.completedAt());
        entity.setDurationMillis(step.durationMillis());
        return entity;
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("trace step payload 序列化失败，写入 null", ex);
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
