package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.account.AccountQueryService;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 账户查询（2026-11-09）：地址 / 支付方式 / 可用优惠券，只读且不返回敏感原文。 */
@Component
public class QueryAccountTool implements ToolExecutor {
    private final AccountQueryService accountQueryService;

    public QueryAccountTool(AccountQueryService accountQueryService) {
        this.accountQueryService = accountQueryService;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.QUERY_ACCOUNT,
                "Query the customer's saved addresses, payment methods and usable coupons (read-only, masked).",
                Map.of(),
                Map.of("addresses", "array", "paymentMethods", "array", "coupons", "array"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        AccountQueryService.AccountSnapshot snapshot = accountQueryService.query(context.customerId());
        context.remember("account", snapshot);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("addresses", snapshot.addresses());
        data.put("paymentMethods", snapshot.paymentMethods());
        data.put("coupons", snapshot.coupons());
        data.put("expiredCouponCount", snapshot.expiredCouponCount());
        return ToolResult.success(ToolNames.QUERY_ACCOUNT, data);
    }
}
