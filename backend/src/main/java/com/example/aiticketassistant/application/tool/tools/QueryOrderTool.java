package com.example.aiticketassistant.application.tool.tools;

import com.example.aiticketassistant.application.routing.QuestionSignals;
import com.example.aiticketassistant.application.tool.ToolExecutor;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.order.Order;
import com.example.aiticketassistant.domain.order.OrderItemRepository;
import com.example.aiticketassistant.domain.order.OrderRepository;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class QueryOrderTool implements ToolExecutor {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;

    public QueryOrderTool(OrderRepository orderRepository, OrderItemRepository orderItemRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Override
    public ToolMetadata metadata() {
        return new ToolMetadata(
                ToolNames.QUERY_ORDER,
                "Query order state. Returns normalized business summary for AI; never exposes persistence entities.",
                Map.of("orderNo", "string", "customerId", "string"),
                Map.of("orderNo", "string", "orderStatus", "string", "businessSummary", "string"));
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, WorkflowContext context) {
        String orderNo = string(arguments, "orderNo", context.orderNo());
        String customerId = string(arguments, "customerId", context.customerId());
        if (isOrderListRequest(context.userMessage()) && (orderNo == null || orderNo.isBlank())) {
            var orders = orderRepository.findRecentByCustomerId(customerId, 20);
            var summaries = orders.stream()
                    .map(this::orderSummary)
                    .toList();
            Map<String, Object> data = Map.of(
                    "customerId", customerId,
                    "totalOrders", summaries.size(),
                    "orders", summaries,
                    "businessSummary", "客户共有 " + summaries.size() + " 个订单。请逐项展示订单号、金额、订单状态和支付状态。");
            context.remember("order", data);
            return ToolResult.success(ToolNames.QUERY_ORDER, data);
        }
        Order order = orderNo == null || orderNo.isBlank()
                ? orderRepository.findLatestByCustomerId(customerId).orElse(null)
                : orderRepository.findByOrderNo(orderNo).orElse(null);
        if (order == null) {
            return ToolResult.failure(ToolNames.QUERY_ORDER, "Order not found for orderNo=" + orderNo + ", customerId=" + customerId);
        }
        Map<String, Object> data = orderSummary(order);
        context.remember("order", data);
        return ToolResult.success(ToolNames.QUERY_ORDER, data);
    }

    private Map<String, Object> orderSummary(Order order) {
        var items = orderItemRepository.findByOrderId(order.id().value()).stream()
                .map(item -> Map.<String, Object>of(
                        "productId", item.productId() == null ? item.sku() : item.productId(),
                        "name", item.name(),
                        "quantity", item.quantity(),
                        "price", item.price()))
                .toList();
        return Map.of(
                "orderNo", order.orderNo(),
                "customerId", order.customerId(),
                "amount", order.amount(),
                "orderStatus", order.orderStatus().name(),
                "paymentStatus", order.paymentStatus().name(),
                "items", items,
                "itemNames", items.stream().map(item -> String.valueOf(item.get("name"))).toList(),
                "canCreateSupportTicket", order.canCreateSupportTicket(),
                "businessSummary", order.aiBusinessSummary());
    }

    /** 订单列表判定走唯一口径（见 {@code QuestionSignals}），本工具不再自带一份关键词表。 */
    private boolean isOrderListRequest(String message) {
        return QuestionSignals.isOrderListRequest(message);
    }

    private String string(Map<String, Object> args, String key, String fallback) {
        Object value = args == null ? null : args.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
