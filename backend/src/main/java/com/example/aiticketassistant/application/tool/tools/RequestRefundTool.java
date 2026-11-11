package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.order.RefundApplicationService;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 退款申请（2026-11-09）：只落申请单，不动钱。 */
@Component
public class RequestRefundTool implements ToolExecutor {
    private final RefundApplicationService refundApplicationService;

    public RequestRefundTool(RefundApplicationService refundApplicationService) {
        this.refundApplicationService = refundApplicationService;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.REQUEST_REFUND,
                "Submit a refund REQUEST for a paid order (never moves money). Rejected if the order is unpaid or already requested.",
                Map.of("orderNo", "string", "reason", "optional string"),
                Map.of("refundId", "string", "orderNo", "string", "amount", "number", "status", "string"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        Object rawOrderNo = arguments == null ? null : arguments.get("orderNo");
        String orderNo = rawOrderNo == null || String.valueOf(rawOrderNo).isBlank()
                ? context.orderNo()
                : String.valueOf(rawOrderNo);
        Object rawReason = arguments == null ? null : arguments.get("reason");
        String reason = rawReason == null ? null : String.valueOf(rawReason);

        RefundApplicationService.RefundResult result =
                refundApplicationService.request(orderNo, context.customerId(), reason);
        context.remember("refundRequest", result);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", result.refundId());
        data.put("orderNo", result.orderNo());
        data.put("amount", result.amount());
        data.put("status", result.status());
        data.put("message", result.message());
        return ToolResult.success(ToolNames.REQUEST_REFUND, data);
    }
}
