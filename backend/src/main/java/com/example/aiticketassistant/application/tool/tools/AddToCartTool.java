package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.cart.CartService;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 加入购物车（2026-11-09）：真落 `cart_items`，重复加购合并数量；不锁库存、不生成订单。 */
@Component
public class AddToCartTool implements ToolExecutor {
    private final CartService cartService;

    public AddToCartTool(CartService cartService) {
        this.cartService = cartService;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.ADD_TO_CART,
                "Add one or more products to the customer's cart. Does NOT create an order and does NOT reserve stock.",
                Map.of("items", "array of {productId, quantity}"),
                Map.of("lines", "array", "totalQuantity", "number", "totalAmount", "number"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        List<CartService.Item> items = new ArrayList<>();
        Object raw = arguments == null ? null : arguments.get("items");
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    Object productId = map.get("productId");
                    Object quantity = map.get("quantity");
                    items.add(new CartService.Item(
                            productId == null ? null : String.valueOf(productId),
                            quantity instanceof Number number ? number.intValue() : 1));
                }
            }
        }
        if (items.isEmpty() && arguments != null && arguments.get("productId") != null) {
            Object quantity = arguments.get("quantity");
            items.add(new CartService.Item(String.valueOf(arguments.get("productId")),
                    quantity instanceof Number number ? number.intValue() : 1));
        }

        CartService.CartSnapshot snapshot = cartService.add(context.customerId(), items);
        context.remember("cart", snapshot);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", "CART_UPDATED");
        data.put("lines", snapshot.lines());
        data.put("totalQuantity", snapshot.totalQuantity());
        data.put("totalAmount", snapshot.totalAmount());
        return ToolResult.success(ToolNames.ADD_TO_CART, data);
    }
}
