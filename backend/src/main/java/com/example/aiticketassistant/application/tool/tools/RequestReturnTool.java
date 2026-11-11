package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.order.ReturnApplicationService;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 退货申请（2026-11-09）：退货 ≠ 退款，只受理申请并转人工核验，不动钱、不改订单状态。 */
@Component
public class RequestReturnTool implements ToolExecutor {
    private final ReturnApplicationService returnApplicationService;

    public RequestReturnTool(ReturnApplicationService returnApplicationService) {
        this.returnApplicationService = returnApplicationService;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.REQUEST_RETURN,
                "Submit a RETURN (send goods back) request for a paid order and open a logistics ticket. Never moves money.",
                Map.of("orderNo", "string", "reason", "optional string"),
                Map.of("returnId", "string", "orderNo", "string", "status", "string", "ticketId", "string"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        Object rawOrderNo = arguments == null ? null : arguments.get("orderNo");
        String orderNo = rawOrderNo == null || String.valueOf(rawOrderNo).isBlank()
                ? context.orderNo()
                : String.valueOf(rawOrderNo);
        Object rawReason = arguments == null ? null : arguments.get("reason");

        ReturnApplicationService.ReturnResult result = returnApplicationService.request(
                orderNo, context.customerId(), rawReason == null ? null : String.valueOf(rawReason));
        context.remember("returnRequest", result);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("returnId", result.returnId());
        data.put("orderNo", result.orderNo());
        data.put("status", result.status());
        data.put("ticketId", result.ticketId());
        data.put("message", result.message());
        return ToolResult.success(ToolNames.REQUEST_RETURN, data);
    }
}
