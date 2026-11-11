package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.order.RefundApplicationService;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.order.RefundRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 退款进度查询（2026-11-09）。
 *
 * <p>为什么必须补：上一版只做了 `REQUEST_REFUND`（能申请），用户申请完问「我的退款到哪了」
 * 却得到"我这边无法查看你的退款状态" —— 能提交、不能查，比不能提交更伤信任。
 */
@Component
public class QueryRefundTool implements ToolExecutor {
    private final RefundApplicationService refundApplicationService;

    public QueryRefundTool(RefundApplicationService refundApplicationService) {
        this.refundApplicationService = refundApplicationService;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.QUERY_REFUND,
                "List the customer's refund requests (optionally for one order number). Read-only.",
                Map.of("orderNo", "optional string"),
                Map.of("refunds", "array", "count", "number", "orderNo", "string"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        Object raw = arguments == null ? null : arguments.get("orderNo");
        String orderNo = raw == null || String.valueOf(raw).isBlank()
                ? context.orderNo()
                : String.valueOf(raw);

        List<RefundRequest> refunds = refundApplicationService.query(context.customerId(), orderNo);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RefundRequest refund : refunds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("refundId", refund.id());
            row.put("orderNo", refund.orderNo());
            row.put("amount", refund.amount());
            row.put("status", refund.status());
            row.put("reason", refund.reason());
            row.put("createdAt", refund.createdAt() == null ? "" : refund.createdAt().toString());
            rows.add(row);
        }

        context.remember("refundQuery", refunds);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", orderNo == null ? "" : orderNo);
        data.put("count", rows.size());
        data.put("refunds", rows);
        return ToolResult.success(ToolNames.QUERY_REFUND, data);
    }
}
