package com.example.aiticketassistant.application.assembler;

import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.account.AccountQueryService;
import com.example.aiticketassistant.application.agent.IntentAgent;
import com.example.aiticketassistant.application.cart.CartService;
import com.example.aiticketassistant.application.order.OrderCancellationService;
import com.example.aiticketassistant.application.order.RefundApplicationService;
import com.example.aiticketassistant.application.order.ReturnApplicationService;
import com.example.aiticketassistant.application.routing.QuestionSignals;
import com.example.aiticketassistant.application.order.OrderConfirmationView;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把各 Agent 的产出拼成**面向用户**的最终回答。
 *
 * <p>三条原则：
 * 1. **结论优先**：知识结论/订单分析直接放在最前，不加"我已完成分析"这类废话；
 * 2. **不暴露内部信息**：意图枚举、置信度、工具 Trace、检索 score、内部 URI 一律不进正文
 *    （它们已经在 SSE 事件里，由前端的工作流卡片 / 工具卡片 / RAG 面板 / Trace 时间线展示）；
 * 3. **空小节不输出**：没创建工单就不提工单，没调工具就不提工具。
 *
 * <p>输出方言与前端 {@code StreamingAnswer} 的迷你 Markdown 渲染器对齐：
 * {@code **小节名**：正文} 与 {@code - 列表项}。
 */
@Component
public class AssistantResponseAssembler {
    /** 北京时间格式化器：对外展示统一用它（Instant 原生是 UTC，直接打印会差 8 小时）。 */
    private static final java.time.format.DateTimeFormatter BEIJING_TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.of("Asia/Shanghai"));
    private static final String NO_ANSWER_HINT = "没能给出有效回答，麻烦补充一下问题细节（例如具体商品编号或订单号）。";

    public String assemble(WorkflowContext context, String orderAnalysis, String knowledgeAnswer) {
        List<String> sections = new ArrayList<>();

        if (hasText(knowledgeAnswer)) {
            sections.add(knowledgeAnswer.trim());
        }
        String tail = sectionsAfterKnowledge(context, orderAnalysis);
        if (hasText(tail)) {
            sections.add(tail);
        }

        if (sections.isEmpty()) {
            sections.add(NO_ANSWER_HINT);
        }
        return String.join("\n\n", sections) + "\n";
    }

    /**
     * 知识结论**之后**的所有小节（订单情况 / 需要你确认 / 工单 / 有一步没能完成），没有就返回空串。
     *
     * <p>为什么要单独暴露：真流式下知识结论是模型逐 token 吐出来的，流结束时我们手里只有"已吐出的正文"，
     * 而剩余小节是确定性的。工作流先流式推正文、再调这里拿尾巴追加，最终文本与 {@link #assemble} 完全一致。
     */
    public String sectionsAfterKnowledge(WorkflowContext context, String orderAnalysis) {
        List<String> sections = new ArrayList<>();
        if (hasText(orderAnalysis)) {
            sections.add("**订单情况**\n" + orderAnalysis.trim());
        }
        String cartSection = cartSection(context);
        if (cartSection != null) {
            sections.add(cartSection);
        }
        String accountSection = accountSection(context);
        if (accountSection != null) {
            sections.add(accountSection);
        }
        String cancellationSection = cancellationSection(context);
        if (cancellationSection != null) {
            sections.add(cancellationSection);
        }
        String refundSection = refundSection(context);
        if (refundSection != null) {
            sections.add(refundSection);
        }
        String refundQuerySection = refundQuerySection(context);
        if (refundQuerySection != null) {
            sections.add(refundQuerySection);
        }
        String returnSection = returnSection(context);
        if (returnSection != null) {
            sections.add(returnSection);
        }
        String returnQuerySection = returnQuerySection(context);
        if (returnQuerySection != null) {
            sections.add(returnQuerySection);
        }
        String confirmationSection = confirmationSection(context);
        if (confirmationSection != null) {
            sections.add(confirmationSection);
        }
        String ticketSection = ticketSection(context);
        if (ticketSection != null) {
            sections.add(ticketSection);
        }
        String failedToolSection = failedToolSection(context);
        if (failedToolSection != null) {
            sections.add(failedToolSection);
        }
        return String.join("\n\n", sections);
    }

    /** 什么都没产出时的兜底话术（不写进正文就会被用户的"空白回答"当成系统挂了）。 */
    public String noAnswerHint() {
        return NO_ANSWER_HINT;
    }

    // ===================== 2026-11-09：购物车 / 账户 / 售后 =====================

    /**
     * 品类候选小节（2026-11-11）：用户只说了品类（「我要买一件羽绒服」）时，**不替他挑**，
     * 列 2~3 个候选并反问要哪一件。数据来自 {@code CategoryCandidateService}，
     * IntentAgent 在判定"只能按品类理解"时放进上下文。
     */
    public String purchaseCandidatesText(WorkflowContext context) {
        if (!(context.recall(IntentAgent.PURCHASE_CANDIDATES_KEY) instanceof java.util.Map<?, ?> raw)) {
            return null;
        }
        Object categoryValue = raw.get("category");
        String category = categoryValue == null ? "这件商品" : String.valueOf(categoryValue);
        Object listValue = raw.get("candidates");
        if (!(listValue instanceof java.util.List<?> candidates) || candidates.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        text.append("**帮你选一下**\n")
                .append("你说的「").append(category).append("」有好几款，我不会替你挑，你选一个我再继续：\n");
        int index = 1;
        for (Object item : candidates) {
            String name = valueOf(item, "name");
            String productId = valueOf(item, "productId");
            String price = valueOf(item, "price");
            text.append(index++).append(". ").append(name)
                    .append("（").append(productId).append("，¥").append(price == null ? "-" : price).append("）\n");
        }
        text.append("\n- 想买哪一款？直接回我名字或编号（例如「就第 1 个」）；也可以说预算，我帮你挑一件合适的。");
        return text.toString();
    }

    /** 从 Map/Record 里取一个字段（候选是 Map，避免这里依赖具体 record 类型）。 */
    private static String valueOf(Object item, String key) {
        if (item instanceof java.util.Map<?, ?> map) {
            Object value = map.get(key);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    /** 购物车小节：列出每行与合计；司机（空车/不支持移除）也如实说。 */
    private String cartSection(WorkflowContext context) {
        if (!(context.recall("cart") instanceof CartService.CartSnapshot cart)) {
            return null;
        }
        if (cart.empty()) {
            String empty = "**购物车**\n购物车现在是空的。说要买哪件（例如「把黑色连帽卫衣加入购物车」）我就帮你放进去。";
            if (QuestionSignals.isRemoveFromCartRequest(context.userMessage())) {
                empty += "\n- 说明：购物车本来就是空的，没有可移除的商品；对话里也不支持移除/清空，请在购物车页面操作。";
            }
            return empty;
        }
        StringBuilder text = new StringBuilder("**购物车**\n");
        for (CartService.Line line : cart.lines()) {
            text.append("- ").append(line.productName())
                    .append(" × ").append(line.quantity())
                    .append("（¥").append(plain(line.lineAmount())).append("）\n");
        }
        text.append("- 合计：").append(cart.totalQuantity()).append(" 件，¥").append(plain(cart.totalAmount()));
        if (QuestionSignals.isRemoveFromCartRequest(context.userMessage())) {
            text.append("\n- 说明：对话里暂不支持移除/清空购物车，请在购物车页面操作（上面是当前车里的内容）。");
        } else {
            text.append("\n- 想下单就说「把购物车里的都下单」，我会先给你一张待确认单。");
        }
        return text.toString();
    }

    /** 账户小节：地址 / 支付方式 / 可用优惠券（都脱敏）。 */
    private String accountSection(WorkflowContext context) {
        if (!(context.recall("account") instanceof AccountQueryService.AccountSnapshot account)) {
            return null;
        }
        StringBuilder text = new StringBuilder("**账户信息**");
        text.append("\n- 收货地址：");
        if (account.addresses().isEmpty()) {
            text.append("没有已保存的地址");
        } else {
            text.append(String.join("；", account.addresses().stream()
                    .map(address -> "%s %s %s%s".formatted(address.recipientName(), address.phone(),
                            address.addressLine(), address.isDefault() ? "（默认）" : ""))
                    .toList()));
        }
        text.append("\n- 支付方式：");
        if (account.paymentMethods().isEmpty()) {
            text.append("没有已绑定的支付方式");
        } else {
            text.append(String.join("；", account.paymentMethods().stream()
                    .map(method -> method.displayLabel() + (method.isDefault() ? "（默认）" : ""))
                    .toList()));
        }
        text.append("\n- 可用优惠券：");
        if (account.coupons().isEmpty()) {
            text.append("暂无可用优惠券");
        } else {
            text.append(String.join("；", account.coupons().stream()
                    .map(coupon -> "%s（%s，满 ¥%s 可用，有效期至 %s）".formatted(
                            coupon.title(), coupon.code(), plain(coupon.minSpend()),
                            coupon.expiresAt().substring(0, 10)))
                    .toList()));
        }
        if (account.expiredCouponCount() > 0) {
            text.append("（另有 %d 张已过期，不计入可用）".formatted(account.expiredCouponCount()));
        }
        return text.toString();
    }

    /** 取消订单小节。 */
    private String cancellationSection(WorkflowContext context) {
        if (!(context.recall("orderCancellation") instanceof OrderCancellationService.CancellationResult result)) {
            return null;
        }
        String released = result.releasedItems().isEmpty()
                ? "无预留库存需要释放"
                : "已释放预留库存：" + String.join("、", result.releasedItems());
        return "**订单已取消**\n订单 %s 已置为 %s；%s。\n%s".formatted(
                result.orderNo(), result.orderStatus(), released, result.message());
    }

    /** 退款进度小节（QUERY_REFUND）：没有记录时也要说清"没查到 + 下一步怎么做"。 */
    @SuppressWarnings("unchecked")
    private String refundQuerySection(WorkflowContext context) {
        Object recalled = context.recall("refundQuery");
        if (!(recalled instanceof java.util.List<?> rawList)) {
            return null;
        }
        java.util.List<com.example.aiticketassistant.domain.order.RefundRequest> refunds =
                (java.util.List<com.example.aiticketassistant.domain.order.RefundRequest>) rawList;
        if (refunds.isEmpty()) {
            return "**退款进度**\n没查到这笔退款申请。如果确实要退，把订单号给我（例如「我要退款 ORD-AI-XXXXXXXX」）我现在就提交。";
        }
        StringBuilder text = new StringBuilder("**退款进度**");
        for (com.example.aiticketassistant.domain.order.RefundRequest refund : refunds) {
            text.append("\n- ").append(refund.id())
                    .append("：订单 ").append(refund.orderNo())
                    .append("，金额 ¥").append(plain(refund.amount()))
                    .append("，状态 ").append(refund.status());
            if (refund.createdAt() != null) {
                // 统一显示北京时间（项目口径见 §6.4），不要直接把 Instant 的 UTC 串丢给用户
                text.append("，提交于 ").append(BEIJING_TIME.format(refund.createdAt()));
            }
        }
        text.append("\n- 状态说明：`REQUESTED` = 待人工审核；`APPROVED` = 已通过（原路退回，到账时间取决于支付渠道）；`REJECTED` = 已驳回。");
        for (com.example.aiticketassistant.domain.order.RefundRequest refund : refunds) {
            if (refund.reviewNote() != null && !refund.reviewNote().isBlank()) {
                text.append("\n- ").append(refund.id()).append(" 审核备注：").append(refund.reviewNote());
            }
        }
        return text.toString();
    }

    /** 退货申请小节。 */
    private String returnSection(WorkflowContext context) {
        if (!(context.recall("returnRequest") instanceof ReturnApplicationService.ReturnResult result)) {
            return null;
        }
        return "**退货申请**\n已受理退货申请 %s（订单 %s，状态 %s），人工工单：%s。\n%s".formatted(
                result.returnId(), result.orderNo(), result.status(),
                result.ticketId() == null ? "—" : result.ticketId(), result.message());
    }

    /** 退货进度小节。 */
    @SuppressWarnings("unchecked")
    private String returnQuerySection(WorkflowContext context) {
        Object recalled = context.recall("returnQuery");
        if (!(recalled instanceof java.util.List<?> rawList)) {
            return null;
        }
        java.util.List<com.example.aiticketassistant.domain.order.ReturnRequest> returns =
                (java.util.List<com.example.aiticketassistant.domain.order.ReturnRequest>) rawList;
        if (returns.isEmpty()) {
            return "**退货进度**\n没查到退货申请。如果确实要退，把订单号给我（例如「我要退货 ORD-AI-XXXXXXXX」）我现在就受理。";
        }
        StringBuilder text = new StringBuilder("**退货进度**");
        for (com.example.aiticketassistant.domain.order.ReturnRequest request : returns) {
            text.append("\n- ").append(request.id())
                    .append("：订单 ").append(request.orderNo())
                    .append("，状态 ").append(request.status());
            if (request.createdAt() != null) {
                text.append("，提交于 ").append(BEIJING_TIME.format(request.createdAt()));
            }
        }
        text.append("\n- 状态说明：`REQUESTED` = 待人工审核；`APPROVED` = 已同意寄回（请按客服指引寄出）；`REJECTED` = 已驳回。");
        for (com.example.aiticketassistant.domain.order.ReturnRequest request2 : returns) {
            if (request2.reviewNote() != null && !request2.reviewNote().isBlank()) {
                text.append("\n- ").append(request2.id()).append(" 审核备注：").append(request2.reviewNote());
            }
        }
        text.append("\n- 退货与退款的区别：退货 = 先把商品寄回并验货，验货通过后再原路退款。");
        return text.toString();
    }

    /** 退款申请小节。 */
    private String refundSection(WorkflowContext context) {
        if (!(context.recall("refundRequest") instanceof RefundApplicationService.RefundResult result)) {
            return null;
        }
        return "**退款申请**\n已提交退款申请 %s（订单 %s，金额 ¥%s，状态 %s）。\n%s".formatted(
                result.refundId(), result.orderNo(), plain(result.amount()), result.status(), result.message());
    }

    /**
     * "需要你确认"小节。
     *
     * <p>2026-11-05 起确认单是**一单多明细**（V9）：这里列出全部商品与合计，让用户在一张卡上一次确认整单，
     * 不必再去数"我是不是少了一件"。
     */
    private String confirmationSection(WorkflowContext context) {
        if (!(context.recall("orderConfirmation") instanceof OrderConfirmationView view)) {
            return null;
        }
        String items = view.items().stream()
                .map(item -> "%s × %d（¥%s）".formatted(
                        item.productName(), item.quantity(), plain(item.lineAmount())))
                .collect(java.util.stream.Collectors.joining("、"));
        return ("**需要你确认**\n已生成待确认订单：%s，共 %d 件，合计 ¥%s。"
                + "请在右侧确认卡片核对商品、金额、收货地址和支付方式后再下单。确认前不会扣款。")
                .formatted(items, view.totalQuantity(), plain(view.totalAmount()));
    }

    private static String plain(java.math.BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }
    /** 只有真的创建/更新了工单才提工单；没有就返回 null（不输出"未创建新工单"这种噪音）。 */
    private String ticketSection(WorkflowContext context) {
        Object ticket = context.recall("ticket");
        if (!(ticket instanceof Map<?, ?> map)) {
            return null;
        }
        return "**工单**\n已创建工单 %s（状态 %s，优先级 %s），可在右侧工单卡片查看。".formatted(
                valueOf(map.get("ticketId")),
                valueOf(map.get("status")),
                valueOf(map.get("priority")));
    }

    /** 业务动作执行失败时必须告诉用户（这不是内部细节，是用户真正关心的结果）。 */
    private String failedToolSection(WorkflowContext context) {
        List<String> failures = context.toolResults().stream()
                .filter(result -> !result.success())
                .map(result -> "- %s：%s".formatted(result.tool(), result.error() == null ? "执行失败" : result.error()))
                .toList();
        return failures.isEmpty() ? null : "**有一步没能完成**\n" + String.join("\n", failures);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String valueOf(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
