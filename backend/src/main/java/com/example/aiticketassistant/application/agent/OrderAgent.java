package com.example.aiticketassistant.application.agent;

import com.example.aiticketassistant.application.conversation.ConversationTurn;
import com.example.aiticketassistant.application.prompt.PromptCatalog;
import com.example.aiticketassistant.application.prompt.PromptIds;
import com.example.aiticketassistant.application.prompt.PromptTemplate;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OrderAgent {
    private final AiClientPort aiClient;
    private final PromptCatalog promptCatalog;

    public OrderAgent(AiClientPort aiClient, PromptCatalog promptCatalog) {
        this.aiClient = aiClient;
        this.promptCatalog = promptCatalog;
    }

    public String analyze(WorkflowContext context) {
        Object orderData = context.toolResults().stream()
                .filter(result -> "QUERY_ORDER".equals(result.tool()))
                .findFirst()
                .map(ToolResult::data)
                .orElse(null);
        if (orderData instanceof Map<?, ?> map && map.get("orders") instanceof List<?> orders) {
            return orderListAnswer(map, orders);
        }
        String orderContext = String.valueOf(orderData == null ? "No order context found" : orderData);
        PromptTemplate prompt = promptCatalog.get(PromptIds.ORDER_AGENT);
        String user = prompt.user(Map.of(
                "userMessage", context.userMessage(),
                "orderToolResult", orderContext,
                "history", ConversationTurn.toPromptBlock(context.conversationHistory())));
        return aiClient.complete(new AiRequest("OrderAgent", prompt.system(), user,
                Map.of("orderContext", orderContext, "userMessage", context.userMessage()), false)).text();
    }

    private String orderListAnswer(Map<?, ?> data, List<?> orders) {
        Object totalOrders = data.get("totalOrders");
        StringBuilder answer = new StringBuilder("你当前一共有 ")
                .append(totalOrders == null ? orders.size() : totalOrders)
                .append(" 个订单。\n\n");
        for (Object item : orders) {
            if (item instanceof Map<?, ?> order) {
                answer.append("- ")
                        .append(itemNames(order))
                        .append("（")
                        .append(value(order, "orderNo"))
                        .append("）：金额 ¥")
                        .append(value(order, "amount"))
                        .append("，订单状态 ")
                        .append(value(order, "orderStatus"))
                        .append("，支付状态 ")
                        .append(value(order, "paymentStatus"))
                        .append("\n");
            }
        }
        return answer.toString().trim();
    }

    private String itemNames(Map<?, ?> map) {
        Object value = map.get("itemNames");
        if (value instanceof List<?> names && !names.isEmpty()) {
            return String.join("、", names.stream().map(String::valueOf).toList());
        }
        return "未记录商品明细";
    }

    private String value(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "-" : String.valueOf(value);
    }
}
