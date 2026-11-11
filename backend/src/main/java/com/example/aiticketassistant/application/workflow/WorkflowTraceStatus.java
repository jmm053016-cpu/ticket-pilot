package com.example.aiticketassistant.application.workflow;

/**
 * 一次工作流最终落库的状态。
 * <p>取值会原样写入 {@code workflow_traces.status}（VARCHAR(32)）。
 */
public enum WorkflowTraceStatus {
    /** 正常跑完（含"错误已被兜成 error 事件"的情况由调用方判定）。 */
    COMPLETED,
    /** 工作流内部抛异常。 */
    FAILED,
    /** 客户端断开 / 取消订阅，流程没跑完（如前端点"停止"）。 */
    CANCELLED
}
