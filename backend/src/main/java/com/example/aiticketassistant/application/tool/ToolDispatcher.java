package com.example.aiticketassistant.application.tool;

import com.example.aiticketassistant.application.workflow.WorkflowContext;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ToolDispatcher {
    private final ToolRegistry registry;
    private final ToolInvocationRecorder recorder;
    private final ToolArgumentGuard argumentGuard;

    public ToolDispatcher(ToolRegistry registry, ToolInvocationRecorder recorder, ToolArgumentGuard argumentGuard) {
        this.registry = registry;
        this.recorder = recorder;
        this.argumentGuard = argumentGuard;
    }

    public List<ToolResult> dispatchAll(List<ToolCall> calls, WorkflowContext context) {
        return calls.stream().map(call -> dispatch(call, context)).toList();
    }

    public ToolResult dispatch(ToolCall call, WorkflowContext context) {
        long start = System.currentTimeMillis();
        ToolResult result;
        if (!ToolNames.isAiCallable(call.tool())) {
            result = ToolResult.failure(call.tool(), "Tool is not AI-callable: " + call.tool());
        } else {
            // 参数白名单校验先于执行：productId / 数量 / 订单号格式都由代码把关，不信模型（§5.4.6）
            String rejection = argumentGuard.rejectReason(call, context);
            result = rejection != null
                    ? ToolResult.failure(call.tool(), rejection)
                    : registry.find(call.tool())
                    .map(executor -> safeExecute(executor, call, context))
                    .orElseGet(() -> ToolResult.failure(call.tool(), "Tool is not registered: " + call.tool()));
        }
        recorder.record(context, call, result, System.currentTimeMillis() - start);
        context.toolResults().add(result);
        return result;
    }

    private ToolResult safeExecute(ToolExecutor executor, ToolCall call, WorkflowContext context) {
        try {
            return executor.execute(call.arguments(), context);
        } catch (Exception ex) {
            return ToolResult.failure(call.tool(), ex.getMessage());
        }
    }
}
