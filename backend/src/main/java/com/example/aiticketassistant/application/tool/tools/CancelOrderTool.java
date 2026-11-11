package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.order.OrderCancellationService;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 取消订单（2026-11-09）：只允许未支付订单，取消后释放预留库存。 */
@Component
public class CancelOrderTool implements ToolExecutor {
    private final OrderCancellationService cancellationService;

    public CancelOrderTool(OrderCancellationService cancellationService) {
        this.cancellationService = cancellationService;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.CANCEL_ORDER,
                "Cancel an unpaid order (CREATED/UNPAID only) and release its reserved stock. Paid orders must use REQUEST_REFUND.",
                Map.of("orderNo", "string"),
                Map.of("orderNo", "string", "orderStatus", "string", "releasedItems", "array"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        Object raw = arguments == null ? null : arguments.get("orderNo");
        String orderNo = raw == null || String.valueOf(raw).isBlank()
                ? context.orderNo()
                : String.valueOf(raw);
        OrderCancellationService.CancellationResult result =
                cancellationService.cancel(orderNo, context.customerId());
        context.remember("orderCancellation", result);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", result.orderNo());
        data.put("orderStatus", result.orderStatus());
        data.put("releasedItems", result.releasedItems());
        data.put("message", result.message());
        return ToolResult.success(ToolNames.CANCEL_ORDER, data);
    }
}
