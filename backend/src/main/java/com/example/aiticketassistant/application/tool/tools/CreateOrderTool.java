package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.cart.CartService;
import com.example.aiticketassistant.application.order.CreateOrderConfirmationService;
import com.example.aiticketassistant.application.order.OrderConfirmationView;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.shared.DomainException;
import com.example.aiticketassistant.application.order.CreateOrderConfirmationService.ItemRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CreateOrderTool implements ToolExecutor {
    private final CreateOrderConfirmationService confirmationService;
    private final CartService cartService;

    public CreateOrderTool(CreateOrderConfirmationService confirmationService, CartService cartService) {
        this.confirmationService = confirmationService;
        this.cartService = cartService;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.CREATE_ORDER,
                "Create ONE pending order confirmation (may contain several items) from trusted catalog, stock, address, and payment method data. Never captures payment.",
                Map.of("items", "array of {productId, quantity} — one entry per product in the user's request",
                        "productId", "trusted product id (single-item shortcut when items is absent)",
                        "quantity", "integer (with productId)",
                        "addressId", "optional trusted address id",
                        "paymentMethodId", "optional trusted payment method id"),
                Map.of("action", "CONFIRM_REQUIRED", "confirmationId", "string", "message", "string",
                        "items", "array", "totalAmount", "number"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        List<ItemRequest> items = fromCart(arguments) ? cartItems(context) : items(arguments);
        String addressId = string(arguments, "addressId", null);
        String paymentMethodId = string(arguments, "paymentMethodId", null);
        // 一张确认单可以有多行商品：用户确认一次就把多件一起下单
        OrderConfirmationView view = confirmationService.create(context, items, addressId, paymentMethodId);
        if (fromCart(arguments)) {
            cartService.clear(context.customerId());   // 下单成功才清车
        }
        context.remember("orderConfirmation", view);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", "CONFIRM_REQUIRED");
        data.put("confirmationId", view.confirmationId());
        data.put("sessionId", view.sessionId());
        data.put("customerId", view.customerId());
        data.put("message", view.message());
        data.put("items", view.items());
        data.put("totalQuantity", view.totalQuantity());
        data.put("totalAmount", view.totalAmount());
        data.put("address", view.address());
        data.put("paymentMethod", view.paymentMethod());
        data.put("expiresAt", view.expiresAt());
        return ToolResult.success(ToolNames.CREATE_ORDER, data);
    }

    /** 是否走「购物车结算」：把整车转成一张待确认单。 */
    private static boolean fromCart(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("fromCart");
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    /** 从购物车构造确认单明细（"把购物车里的都下单"走这条路）。 */
    private List<ItemRequest> cartItems(WorkflowContext context) {
        CartService.CartSnapshot cart = cartService.snapshot(context.customerId());
        if (cart.empty()) {
            throw new DomainException("购物车是空的，先把想买的商品加进购物车吧");
        }
        return cart.lines().stream()
                .map(line -> new ItemRequest(line.productId(), line.quantity()))
                .toList();
    }

    /**
     * 参数有两种形态：新的 {@code items:[{productId,quantity}]}，和旧的 {@code productId + quantity}。
     * 旧的保留是为了兼容模型偶发按老 schema 出参（少一件总比整单失败强）。
     */
    private List<ItemRequest> items(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("items");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            List<ItemRequest> items = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    String productId = map.get("productId") == null ? null : String.valueOf(map.get("productId"));
                    Object quantity = map.get("quantity");
                    items.add(new ItemRequest(productId, quantity instanceof Number number ? number.intValue() : 1));
                }
            }
            if (!items.isEmpty()) {
                return items;
            }
        }
        return List.of(new ItemRequest(string(arguments, "productId", null), integer(arguments, "quantity", 1)));
    }
    private String string(Map<String, Object> args, String key, String fallback) {        Object value = args == null ? null : args.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private int integer(Map<String, Object> args, String key, int fallback) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
