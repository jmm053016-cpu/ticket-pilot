package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.order.ReturnApplicationService;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.order.ReturnRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 退货进度查询（2026-11-09）。 */
@Component
public class QueryReturnTool implements ToolExecutor {
    private final ReturnApplicationService returnApplicationService;

    public QueryReturnTool(ReturnApplicationService returnApplicationService) {
        this.returnApplicationService = returnApplicationService;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.QUERY_RETURN,
                "List the customer's return requests (optionally for one order number). Read-only.",
                Map.of("orderNo", "optional string"),
                Map.of("returns", "array", "count", "number"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        Object raw = arguments == null ? null : arguments.get("orderNo");
        String orderNo = raw == null || String.valueOf(raw).isBlank()
                ? context.orderNo()
                : String.valueOf(raw);

        List<ReturnRequest> returns = returnApplicationService.query(context.customerId(), orderNo);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReturnRequest request : returns) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("returnId", request.id());
            row.put("orderNo", request.orderNo());
            row.put("status", request.status());
            row.put("reason", request.reason());
            row.put("createdAt", request.createdAt() == null ? "" : request.createdAt().toString());
            rows.add(row);
        }
        context.remember("returnQuery", returns);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", orderNo == null ? "" : orderNo);
        data.put("count", rows.size());
        data.put("returns", rows);
        return ToolResult.success(ToolNames.QUERY_RETURN, data);
    }
}
