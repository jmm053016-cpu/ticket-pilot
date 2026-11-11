package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.cart.CartService;
import com.example.aiticketassistant.application.routing.QuestionSignals;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 查看购物车（2026-11-09）。 */
@Component
public class ViewCartTool implements ToolExecutor {
    private final CartService cartService;

    public ViewCartTool(CartService cartService) {
        this.cartService = cartService;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.VIEW_CART,
                "List everything currently in the customer's cart with prices and totals.",
                Map.of(),
                Map.of("lines", "array", "totalQuantity", "number", "totalAmount", "number"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        CartService.CartSnapshot snapshot = cartService.snapshot(context.customerId());
        context.remember("cart", snapshot);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lines", snapshot.lines());
        data.put("totalQuantity", snapshot.totalQuantity());
        data.put("totalAmount", snapshot.totalAmount());
        data.put("empty", snapshot.empty());
        data.put("removeUnsupported", QuestionSignals.isRemoveFromCartRequest(context.userMessage()));
        return ToolResult.success(ToolNames.VIEW_CART, data);
    }
}
