package com.example.aiticketassistant.application.workflow;

import java.time.Instant;

/**
 * 工作流 trace 落库端口。
 *
 * <p>调用方在请求结束时调用一次；实现方负责把 {@link WorkflowContext#trace()} 的
 * 整体信息与全部步骤一次性写入 {@code workflow_traces} / {@code workflow_trace_steps}。
 *
 * <p>约定：**写 trace 失败绝不能影响已经推给用户的回答**，实现方必须自行兜住异常。
 */
public interface WorkflowTraceRecorder {
    /**
     * @param context     已结束的工作流上下文
     * @param status      最终状态
     * @param completedAt 结束时间
     */
    void record(WorkflowContext context, WorkflowTraceStatus status, Instant completedAt);
}
