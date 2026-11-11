package com.example.aiticketassistant.infrastructure.ai;

import com.example.aiticketassistant.application.agent.AiClientPort;
import com.example.aiticketassistant.application.agent.AiRequest;
import com.example.aiticketassistant.application.agent.AiResponse;
import com.example.aiticketassistant.application.routing.PurchaseItem;
import com.example.aiticketassistant.application.routing.QuestionSignals;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 规则兜底替身（无 API Key 或模型不可用时生效）。
 *
 * <p>它返回的意图 JSON 必须和真实模型**同一套口径**，否则"有没有配 Key"会改变系统行为。
 * 因此订单列表、下单意图、商品识别、购买数量一律走 {@link QuestionSignals}（唯一关键词表），
 * 本类只保留"生成哪段 JSON 文本"的逻辑。
 */
@Component
@ConditionalOnProperty(prefix = "assistant.ai", name = "provider", havingValue = "fallback", matchIfMissing = true)
public class FallbackAiClientAdapter implements AiClientPort {

    @Override
    public AiResponse complete(AiRequest request) {
        String user = request.userPrompt() == null ? "" : request.userPrompt();
        String message = resolveUserMessage(request);
        if (request.expectJson()) {
            if (QuestionSignals.isOrderListRequest(message)) {
                return AiResponse.fallback("""
                        {"intent":"QUERY_ORDER","title":"订单汇总查询","priority":"MEDIUM","confidence":0.58,"tool_calls":[{"tool":"QUERY_ORDER","arguments":{}}]}
                        """);
            }
            List<PurchaseItem> purchaseItems = QuestionSignals.purchaseItems(message);
            if (!purchaseItems.isEmpty()) {
                // 一个 CREATE_ORDER 带 items[] 数组（确认单支持多明细）：少给一件就是静默丢单（§5.4.2）
                String items = purchaseItems.stream()
                        .map(item -> "{\"productId\":\"%s\",\"quantity\":%d}"
                                .formatted(item.productId(), item.quantity()))
                        .collect(java.util.stream.Collectors.joining(","));
                return AiResponse.fallback("""
                        {"intent":"GENERAL_SUPPORT","title":"安全下单确认","priority":"MEDIUM","confidence":0.6,"tool_calls":[{"tool":"CREATE_ORDER","arguments":{"items":[%s]}}]}
                        """.formatted(items));
            }
            if (QuestionSignals.hasPurchaseIntent(message)) {
                return AiResponse.fallback("""
                        {"intent":"GENERAL_SUPPORT","title":"需要选择商品","priority":"MEDIUM","confidence":0.5,"tool_calls":[]}
                        """);
            }
            if (message.contains("支付") && message.contains("工单")) {
                return AiResponse.fallback("""
                        {"intent":"CREATE_TICKET","title":"支付失败","priority":"HIGH","confidence":0.62,"tool_calls":[{"tool":"CREATE_TICKET","arguments":{"title":"支付失败","description":"用户反馈支付失败","priority":"HIGH"}}]}
                        """);
            }
            if (message.contains("订单") && (message.contains("怎么办") || message.contains("没生成"))) {
                return AiResponse.fallback("""
                        {"intent":"ORDER_AND_KNOWLEDGE","title":"支付成功但订单未生成","priority":"HIGH","confidence":0.61,"tool_calls":[{"tool":"QUERY_ORDER","arguments":{}},{"tool":"SEARCH_KNOWLEDGE","arguments":{"query":"支付成功但订单没生成怎么办","limit":5}}]}
                        """);
            }
            if (message.contains("订单") || message.contains("ORD-")) {
                return AiResponse.fallback("""
                        {"intent":"QUERY_ORDER","title":"订单状态查询","priority":"MEDIUM","confidence":0.58,"tool_calls":[{"tool":"QUERY_ORDER","arguments":{}}]}
                        """);
            }
            if (message.contains("退款") || message.contains("怎么") || message.contains("FAQ") || message.contains("知识")) {
                return AiResponse.fallback("""
                        {"intent":"KNOWLEDGE_QA","title":"知识库咨询","priority":"MEDIUM","confidence":0.55,"tool_calls":[{"tool":"SEARCH_KNOWLEDGE","arguments":{"limit":5}}]}
                        """);
            }
            return AiResponse.fallback("""
                    {"intent":"GENERAL_SUPPORT","title":"智能客服咨询","priority":"MEDIUM","confidence":0.5,"tool_calls":[]}
                    """);
        }
        if ("OrderAgent".equals(request.agentName())) {
            return AiResponse.fallback(orderFallback(user));
        }
        if ("KnowledgeAgent".equals(request.agentName())) {
            return AiResponse.fallback(knowledgeFallback(user, message));
        }
        if ("DirectQwen".equals(request.agentName())) {
            return AiResponse.fallback(directFallback(message));
        }
        return AiResponse.fallback("AI 服务未配置，当前使用本地规则兜底响应。");
    }

    /** 规则兜底的预算金额：解析不出时给 500，保证文案里总有数字。 */
    private int extractBudget(String message) {
        Integer amount = QuestionSignals.extractBudgetAmount(message);
        return amount == null ? 500 : amount;
    }

    /** 「1000元最多可以买几件衣服」：品类词 + 预算计数问法，两者口径都在 QuestionSignals。 */
    private boolean isMaxCountClothingQuestion(String message) {
        return QuestionSignals.hasCategoryWord(message) && QuestionSignals.isBudgetCountingQuestion(message);
    }

    /**
     * 解析「用户当前这句话」。
     *
     * <p>优先取 {@link AiRequest#structuredContext()} 里的 {@code userMessage}（调用方直接给），
     * 解析提示词只作兜底：提示词里现在含会话历史与检索来源，按文本猜关键词会被历史污染。
     */
    private String resolveUserMessage(AiRequest request) {
        if (request.structuredContext() != null) {
            Object structured = request.structuredContext().get("userMessage");
            if (structured != null && !String.valueOf(structured).isBlank()) {
                return String.valueOf(structured);
            }
        }
        return extractUserMessage(request.userPrompt() == null ? "" : request.userPrompt());
    }

    private String extractUserMessage(String userPrompt) {
        String marker = "User message:";
        int start = userPrompt.indexOf(marker);
        if (start < 0) {
            return userPrompt;
        }
        int valueStart = start + marker.length();
        int end = userPrompt.indexOf('\n', valueStart);
        return (end < 0 ? userPrompt.substring(valueStart) : userPrompt.substring(valueStart, end)).trim();
    }

    private String directFallback(String message) {
        if (isMaxCountClothingQuestion(message)) {
            int budget = extractBudget(message);
            int unitPrice = 99;
            int quantity = budget / unitPrice;
            int total = quantity * unitPrice;
            int remaining = budget - total;
            return "%d元最多可以买%d件T恤，按每件%d元计算，合计%d元，剩余%d元。".formatted(budget, quantity, unitPrice, total, remaining);
        }
        if (message.contains("支付") && message.contains("订单") && (message.contains("没生成") || message.contains("怎么办"))) {
            return "建议先确认支付渠道是否扣款成功，保存支付凭证；如果订单仍未生成，联系商家客服核验支付流水，必要时申请补单或退款。";
        }
        if (message.contains("退款") && message.contains("怎么")) {
            return "一般先进入订单详情查看退款入口，提交退款原因和凭证后等待审核；如果没有对应订单或入口，需要联系商家客服处理。";
        }
        return "这是普通咨询，我可以直接回答；如果要查询你的订单、账户或执行下单/退款等业务动作，需要进入业务流程。";
    }

    private String orderFallback(String userPrompt) {
        if (userPrompt.contains("totalOrders=") && userPrompt.contains("orders=")) {
            return orderListFallback(userPrompt);
        }
        if (userPrompt.contains("Order tool result: No order context found")) {
            return "未查询到订单上下文，无法给出订单状态判断。请提供订单号或先执行订单查询。";
        }
        if (userPrompt.contains("orderStatus=FAILED") || userPrompt.contains("订单生成失败")) {
            return "订单查询显示支付已完成但订单状态异常，应核验支付流水并进入补偿处理。";
        }
        if (userPrompt.contains("paymentStatus=PAID")) {
            return "订单已支付，请结合订单状态判断是否需要补单、退款或人工复核。";
        }
        return "已读取订单上下文，请按当前订单状态选择查询、补偿或人工处理。";
    }

    private String orderListFallback(String userPrompt) {
        Matcher count = Pattern.compile("totalOrders=(\\d+)").matcher(userPrompt);
        String total = count.find() ? count.group(1) : "多";
        StringBuilder answer = new StringBuilder("你当前一共有 ").append(total).append(" 个订单。\n");
        Matcher orderMatcher = Pattern.compile("orderNo=([^,}]+).*?amount=([0-9.]+).*?orderStatus=([A-Z_]+).*?paymentStatus=([A-Z_]+)").matcher(userPrompt);
        while (orderMatcher.find()) {
            answer.append("- ")
                    .append(orderMatcher.group(1).trim())
                    .append("：金额 ¥")
                    .append(orderMatcher.group(2).trim())
                    .append("，订单状态 ")
                    .append(orderMatcher.group(3).trim())
                    .append("，支付状态 ")
                    .append(orderMatcher.group(4).trim())
                    .append("\n");
        }
        return answer.toString().trim();
    }

    private String knowledgeFallback(String userPrompt, String question) {
        String sources = extractSources(userPrompt);
        if (sources.isBlank()) {
            return "未检索到直接匹配的知识库资料，建议转人工复核或补充问题细节。";
        }
        // 只判断「用户当前这句话」：整个 prompt 里含会话历史与检索来源，
        // 若拿它做关键词匹配，历史里出现过的「退款」会把当前问题劫持成退款答复（已踩过）。
        if (question.contains("退款")) {
            return "知识库建议：退款问题需先确认支付状态、退款渠道和预计到账时间。";
        }
        if (question.contains("支付")) {
            return "知识库建议：支付异常需保留支付凭证，核验支付流水后再判断补单或退款。";
        }
        return "根据知识库：" + System.lineSeparator() + sources;
    }

    private String extractQuestion(String userPrompt) {
        String marker = "User question:";
        int start = userPrompt.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int from = start + marker.length();
        int end = userPrompt.indexOf("Retrieved sources:", from);
        if (end < 0) {
            end = userPrompt.length();
        }
        return userPrompt.substring(from, end).trim();
    }

    private String extractSources(String userPrompt) {
        String marker = "Retrieved sources:";
        int index = userPrompt.indexOf(marker);
        if (index < 0) {
            return "";
        }
        String block = userPrompt.substring(index + marker.length()).trim();
        if (block.isBlank()) {
            return "";
        }
        StringBuilder sources = new StringBuilder();
        int lines = 0;
        for (String line : block.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            if (lines++ >= 5) {
                break;
            }
            if (sources.length() > 0) {
                sources.append('\n');
            }
            sources.append(line.trim());
        }
        return sources.toString();
    }
}
