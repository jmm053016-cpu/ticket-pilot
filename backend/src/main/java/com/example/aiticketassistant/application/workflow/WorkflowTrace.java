package com.example.aiticketassistant.application.workflow;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class WorkflowTrace {
    private final String traceId = "trace-" + UUID.randomUUID();
    private final Instant startedAt = Instant.now();
    // Order / Knowledge Agent 并行执行时会同时写入 trace，使用写时复制列表保证并发安全。
    private final List<WorkflowStep> steps = new CopyOnWriteArrayList<>();

    public WorkflowStep startStep(String name, String kind, Map<String, Object> input) {
        WorkflowStep step = new WorkflowStep(name, kind, WorkflowStepStatus.STARTED, Instant.now(), null, input == null ? Map.of() : input, Map.of());
        steps.add(step);
        return step;
    }

    public void complete(WorkflowStep step, Map<String, Object> output) {
        replace(step, step.complete(output == null ? Map.of() : output));
    }

    public void fail(WorkflowStep step, Map<String, Object> output) {
        replace(step, step.fail(output == null ? Map.of() : output));
    }

    private void replace(WorkflowStep oldStep, WorkflowStep newStep) {
        int index = steps.indexOf(oldStep);
        if (index >= 0) {
            steps.set(index, newStep);
        }
    }

    public String traceId() { return traceId; }
    public Instant startedAt() { return startedAt; }
    public List<WorkflowStep> steps() { return Collections.unmodifiableList(steps); }
}
