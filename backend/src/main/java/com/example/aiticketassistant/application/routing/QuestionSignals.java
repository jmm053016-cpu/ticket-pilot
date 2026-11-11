package com.example.aiticketassistant.application.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「这句话属于哪类需求」的**唯一关键词表**与**唯一判定口径**（全部是纯函数）。
 *
 * <p>为什么必须唯一：同一批关键词曾经在四个地方各写一遍
 * （{@code AssistantWorkflowService.shouldUseAgentWorkflow}、{@code IntentAgent.heuristicIntent}、
 * {@code IntentAgent.applyCatalogGuard}、{@code FallbackAiClientAdapter}），而且口径互不相同 ——
 * 路由用的"下单"判定比意图判定少一条正则、意图用的"商品问题"判定比路由少几个品类词，
 * 于是同一个需求换个说法就会出现「进了工作流却不检索」或「没进工作流」两种结果。
 * 改判定规则必须同步多处，是最容易改坏的地方。
 *
 * <p>现在所有关键词与正则只出现在本类，调用方只调语义方法：
 * <ul>
 *   <li>{@code AssistantWorkflowService.shouldUseAgentWorkflow} —— 要不要进多 Agent 工作流；</li>
 *   <li>{@code IntentAgent.heuristicIntent / heuristicToolCalls} —— 模型没给出可用意图或工具时的兜底；</li>
 *   <li>{@code IntentAgent.applyCatalogGuard / applyBudgetPlanGuard} —— 确定性补检索、补预算测算；</li>
 *   <li>{@code FallbackAiClientAdapter} —— 规则兜底替身返回的意图 JSON；</li>
 *   <li>{@code Bm25SearchAdapter} / {@code PlanBudgetTool} —— 汇总加权与预算参数。</li>
 * </ul>
 *
 * <p>**维护红线**：新增/调整关键词只改本类的表，不要在调用方再写 {@code contains("…")}，
 * 也不要为了局部方便复制一份表出去 —— 那正是本类存在的理由。
 */
public final class QuestionSignals {

    // ===================== 关键词表（唯一事实来源） =====================

    /** 商品品类词。 */
    private static final String[] CATEGORY = {
            "衣服", "服装", "商品", "T恤", "衬衫", "牛仔裤", "卫衣", "连衣裙", "夹克", "半身裙",
            "Polo", "polo", "毛衣", "大衣", "裤子", "外套", "鞋", "配饰", "单品",
            // V10（30 → 60 件）新增品类：不补这些词，问「靴子多少钱」不会进商品问答，只会闲聊作答
            "靴", "凉鞋", "高跟鞋", "运动鞋", "套装", "家居服", "睡衣", "睡袍", "泳衣",
            "帽子", "手套", "太阳镜", "墨镜", "围巾", "背包", "双肩包", "冲锋衣", "西装",
            "瑜伽裤", "童装", "开衫", "毛衣衫", "长裙", "针织"};
    /** 检索型线索：多与"查/比/问价格"相关。 */
    private static final String[] CATALOG_CUES = {
            "商品目录", "服装目录", "商品编号", "编号", "价格", "多少钱", "多少元", "刚好",
            "便宜", "最便宜", "最贵", "更便宜", "低于", "小于", "少于", "以内", "不超过",
            "预算", "预算有限", "哪几种", "哪些", "有什么"};
    /** 购物型线索：多与"推荐/搭配/买几件"相关。 */
    private static final String[] SHOPPING_CUES = {
            "最多", "几件", "多少件", "买几件", "能买几件", "可以买几件", "预算", "搭配",
            "推荐", "建议", "比较", "怎么选", "划算", "省钱"};
    /** 只有购物线索时，必须带上这些"钱/量"的量词才认为是商品问题（避免"推荐一个退款流程"误判）。 */
    private static final String[] MONEY_OR_QUANTITY = {"预算", "搭配", "几件", "多少件", "元"};
    /** 汇总型问题的线索：答案需要"整张表"而不是单个商品。 */
    private static final String[] AGGREGATE_CUES = {
            "预算", "最多", "几件", "多少件", "能买", "可以买", "搭配", "组合", "一共", "总共",
            "全部", "所有", "价格表", "哪几种", "哪些", "有什么", "最便宜", "最贵", "便宜", "低于", "小于", "推荐"};

    /**
     * 预算/选购类措辞。
     *
     * <p>用途只有一个：**与"明确下单"互斥** —— 「最多可以买几件衣服」里的"买"不是下单动作，
     * 若判成购买就会生成待确认订单，干扰用户。
     */
    private static final String[] BUDGET_CUES = {
            "最多", "几件", "多少件", "买几件", "能买几件", "可以买几件", "预算", "搭配",
            "推荐", "建议", "比较", "怎么选"};

    /** 明确下单动作（不含"买"这种泛指："买过一件"不能触发下单）。 */
    private static final String[] PURCHASE_CUES = {
            "买一件", "买一条", "买一个", "买一套", "买两件", "买2件", "买3件",
            "我要买", "我想买", "想买", "要买", "帮我买", "给我买",
            "购买", "立即购买", "下单", "帮我下单", "我要下单", "创建订单", "确认下单",
            "来一件", "来一条", "来一个", "来一套", "入手", "拿一件", "拿一条", "拿一个"};
    /** 「买 2 件」这类把数量写在动词后面的说法，上面的词表覆盖不到。 */
    private static final Pattern PURCHASE_WITH_QUANTITY = Pattern.compile(".*(?:买|购买|下单)\\s*\\d+\\s*(?:件|个|条|套).*");
    /** 「各 2 件」这类**逐商品**数量（多商品下单时用）。 */
    private static final Pattern PER_ITEM_QUANTITY = Pattern.compile("各\\s*(\\d+)\\s*(?:件|个|条|套)");
    /** 下单数量的解析口径（与 {@link #PURCHASE_WITH_QUANTITY} 同一套动词与量词）。 */
    private static final Pattern PURCHASE_QUANTITY = Pattern.compile("(?:买|购买|下单)\\s*(\\d+)\\s*(件|个|条|套)");

    /** 购物车动作。 */
    private static final String[] CART_CUES = {"加入购物车", "加购物车", "放购物车", "移除购物车", "删除购物车", "清空购物车"};
    /** 加购动词（和"购物车"同现才算加购：「把黑色卫衣加入购物车」）。 */
    private static final String[] CART_ADD_CUES = {
            "加入购物车", "加购物车", "加到购物车", "放进购物车", "放购物车", "添加到购物车", "添加购物车"};
    /** 移除/清空类措辞（对话里暂不支持，要明确告知而不是装作做完了）。 */
    private static final String[] CART_REMOVE_CUES = {"移除购物车", "删除购物车", "清空购物车", "从购物车删", "从购物车移除"};
    /** 退款**动作**（区别于"怎么退款/退款要几天"这类知识问题）。 */
    private static final String[] REFUND_ACTION_CUES = {
            "申请退款", "我要退款", "帮我退款", "给我退款", "退款申请", "要退款", "办理退款", "直接退款"};
    /** 退款**知识**问法：命中这些就按"问流程"处理，不触发退款申请。 */
    private static final String[] REFUND_KNOWLEDGE_CUES = {
            "怎么退款", "如何退款", "退款流程", "退款政策", "退款规则", "要几天", "多久", "什么时候到账"};
    /**
     * 退款**进度查询**（2026-11-09 补）：问的是"我这张申请到哪一步了"，要查库而不是讲流程。
     *
     * <p>「退款到哪」原先被错归到知识问法里 —— 用户问自己那单的进度，却得到一段"退款流程说明"。
     */
    private static final String[] REFUND_STATUS_CUES = {
            "退款进度", "退款到哪", "退款状态", "退款申请状态", "我的退款", "查退款", "退款单号",
            "退款通过了吗", "退款审批", "退款处理到"};
    /** 购物车**查看**意图词：只认明确要看车的说法，"购物车会不会自动清空"这种规则问句不算。 */
    private static final String[] CART_VIEW_CUES = {
            "我的购物车", "购物车里有什么", "购物车里有哪些", "购物车里剩", "购物车里还", "购物车里有", "查看购物车",
            "看看购物车", "看下购物车", "看购物车", "购物车看一下", "购物车里有啥", "购物车清单"};
    /** 退货**动作**（退货是"货寄回"，与"退款"是两回事；换货走同一条人工工单）。 */
    private static final String[] RETURN_ACTION_CUES = {
            "申请退货", "我要退货", "帮我退货", "退货申请", "要退货", "办理退货", "申请换货", "我要换货"};
    /** 退货进度查询。 */
    private static final String[] RETURN_STATUS_CUES = {
            "退货进度", "退货到哪", "退货状态", "退货申请状态", "我的退货", "查退货", "退货单号"};
    /** 退货知识问法（答流程，不落单）。 */
    private static final String[] RETURN_KNOWLEDGE_CUES = {
            "怎么退货", "如何退货", "退货流程", "退货政策", "退货规则", "退货时效", "退货要几天"};
    /** 订单列表类措辞（要的是"一览"，不是单个订单号）。 */
    private static final String[] ORDER_LIST_CUES = {
            "几个订单", "多少订单", "所有订单", "全部订单", "我的订单", "下了什么订单",
            "查我的订单", "查询我的订单", "帮我查订单", "帮我查一下我的订单", "我有几个订单"};
    /** 账户/资料类措辞。 */
    private static final String[] ACCOUNT_CUES = {"我的账户", "我的地址", "收货地址", "支付方式", "余额", "积分", "个人信息",
            // 2026-11-09：优惠券是账户数据，问它要能进账户查询（原来只会得到"我查不到"）
            "优惠券", "我的券", "几张券", "代金券"};
    /** 订单生命周期动作（要改状态或走售后流程）。 */
    private static final String[] ORDER_LIFECYCLE_CUES = {"取消订单", "确认收货", "申请退款", "帮我退款", "修改地址"};
    /** 工单动作（改既有工单）。 */
    private static final String[] TICKET_ACTION_CUES = {"更新工单", "关闭工单", "升级工单", "我要投诉"};

    /**
     * 纯闲聊白名单（2026-11-11）：只用来走"直答快路径"，**不再用它决定"要不要问模型"**。
     *
     * <p>为什么还留一份：路由交给模型后，每句闲聊都要多一次意图调用的往返（0.5~1s）。
     * 这几句是确定的寒暄，省掉这次调用不影响正确性。
     */
    private static final String[] CHITCHAT_CUES = {
            "你好", "您好", "hi", "hello", "在吗", "谢谢", "多谢", "感谢", "辛苦了",
            "你是谁", "你能做什么", "你能干什么", "介绍一下你", "早上好", "中午好", "晚上好", "再见", "拜拜"};
    /** 闲聊判定的长度上限：超过这个长度多半不是寒暄（宁可贵一点，也不要把业务问题当闲聊）。 */
    private static final int CHITCHAT_MAX_LENGTH = 20;

    /** 建工单：动词 + 工单，中间允许几个字的修饰（"创建支付失败工单"）。 */
    private static final Pattern TICKET_CREATE_PATTERN = Pattern.compile("(创建|新建|提交|发起|开|建).{0,8}工单");
    /** 订单号 `ORD-xxx`。 */
    private static final Pattern ORDER_NO_PATTERN = Pattern.compile("\\bORD-[A-Za-z0-9-]+\\b");
    /**
     * 从**用户消息**里抽订单号（`ORD-xxx`），抽不到返回空串。
     *
     * <p>为什么要它：`WorkflowContext.orderNo` 原先只来自请求参数，用户直接把订单号写进句子里
     * （「帮我取消订单 ORD-AI-BEC2138C」）时工作流其实拿不到它 —— 于是取消/退款工具只能报"请提供订单号"。
     */
    public static String orderNoIn(String message) {
        Matcher matcher = ORDER_NO_PATTERN.matcher(message == null ? "" : message);
        return matcher.find() ? matcher.group() : "";
    }

    /** 商品编号 `CLOTH-XXX-001`。 */
    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("CLOTH-[A-Z]+-\\d{3}");

    /**
     * 商品名称 → productId（包含匹配：具体名称在前、通用品类词在后）。
     *
     * <p>同一张表以前在 {@code IntentAgent} 与 {@code FallbackAiClientAdapter} 里各有一份，
     * 新增商品时只改一处就会出现「真模型能下单、规则兜底不能」的分歧。
     */
    private static final Map<String, String> PRODUCT_NAME_TO_ID = productIds();

    private QuestionSignals() {}

    // ===================== 路由组合（改表前先看这里） =====================

    /**
     * 这条消息要不要进多 Agent 工作流（否则走直答）。
     *
     * <p>**为什么要放在本类**：这九路 OR 关系原先是写在
     * {@code AssistantWorkflowService.shouldUseAgentWorkflow} 里的 —— 于是"哪几个信号决定路由"
     * 只在那一个文件里可见，改表的人看不到全貌。搬回这里之后，
     * 「表 → 信号 → 消费者」三层关系都集中在本类，配合
     * {@code QuestionSignalsImpactTest} 的影响面矩阵，改一张表能立刻看出会波及哪些路。
     */
    public static boolean needsAgentWorkflow(String message) {
        String value = message == null ? "" : message;
        return hasOrderNo(value)
                || isCatalogOrBudgetQuestion(value)
                // 购买意图**且能定位到具体商品**才进工作流（2026-11-05）：
                // 只有"买"没有商品时，工作流里没有可下单的东西，一个工具都不会被调用，
                // 正文只剩空文案 → 用户看到"没能给出有效回答"。
                // 这类问法交给直答路径：先检索（语义向量能把「围脖」召回「围巾」），再让模型反问要哪一件。
                || purchaseProductId(value) != null
                || isCartAction(value)
                // 2026-11-09：购物车三态 + 取消/退款动作也要进工作流（它们现在有真工具了）。
                // 漏掉一行就是"问法换个说法就掉到直答路径、工具根本不执行"。
                || isAddToCartRequest(value)
                || isViewCartRequest(value)
                || isCartCheckoutRequest(value)
                || isCancelOrderRequest(value)
                || isRefundRequest(value)
                || isRefundStatusQuery(value)
                || isReturnRequest(value)
                || isReturnStatusQuery(value)
                || isOrderListRequest(value)
                || isAccountQuery(value)
                || isOrderLifecycleAction(value)
                || isTicketCreateRequest(value)
                || isTicketActionRequest(value);
    }

    // ===================== 路由/意图：这句话属于哪类需求 =====================

    /**
     * 商品/价格/预算类问题（需要商品数据才能回答）。
     *
     * <p>两种成立方式：
     * <ol>
     *   <li>有品类词，且带检索线索（价格/多少钱/便宜…）或购物线索（推荐/搭配/几件…）；</li>
     *   <li>没有品类词，但购物线索 + 钱/量词（预算/搭配/几件/元）——「1000元预算可以怎么搭配」。</li>
     * </ol>
     * 明确下单（走 {@code CREATE_ORDER}）不在此列，避免干扰确认卡片；
     * 「推荐一个退款流程」「支付失败怎么办」也不会误判。
     */
    public static boolean isCatalogOrBudgetQuestion(String message) {
        String value = message == null ? "" : message;
        boolean hasCategory = hasCategoryWord(value);
        boolean hasShoppingCue = containsAny(value, SHOPPING_CUES);
        if (hasCategory && (hasShoppingCue || containsAny(value, CATALOG_CUES))) {
            return true;
        }
        return hasShoppingCue && containsAny(value, MONEY_OR_QUANTITY);
    }

    /** 句中是否出现了具体品类词（衣服/卫衣/大衣…）。 */
    public static boolean hasCategoryWord(String message) {
        return containsAny(message == null ? "" : message, CATEGORY);
    }

    /** 是否明确在购买某个能定位到具体商品的东西（下单动作 + 商品可识别）。 */
    public static boolean hasPurchaseIntent(String message) {
        String value = message == null ? "" : message;
        if (value.isBlank() || isBudgetQuestion(value)) {
            return false;
        }
        return containsAny(value, PURCHASE_CUES) || PURCHASE_WITH_QUANTITY.matcher(value).matches();
    }

    /**
     * 明确下单且能定位到具体商品时返回 productId，否则 {@code null}。
     *
     * <p>两者缺一都不能下单：只有"买"没商品 → 让模型反问；只有商品名没动作 → 那是在问价格。
     *
     * <p>多个商品时只返回**第一个**（按句子出现顺序）。要全部商品请用 {@link #purchaseItems}：
     * 2026-11-05 修过一个真实 bug —— 「我想要买米色帆布双肩包和藏青帆布鞋」只下成了后者，
     * 因为当时全项目只有"返回一个 productId"这条路，且顺序取决于商品名表的行序。
     */
    public static String purchaseProductId(String message) {
        List<PurchaseItem> items = purchaseItems(message);
        return items.isEmpty() ? null : items.get(0).productId();
    }

    /**
     * 一句话里出现的**全部**待购买商品，按句子出现顺序返回（同一商品只出现一次）。
     *
     * <p>为什么要有多个：用户说「买 A 和 B」时只处理一个就是**静默丢单**（真实故障，见 §5.4.2）。
     * 识别规则：
     * <ol>
     *   <li>先认句中的商品编号（`CLOTH-XXX-001`），再认商品名；</li>
     *   <li>按**出现位置**排序，同位置**长名优先** —— 否则「米色帆布双肩包」会被「双肩包」「包」抢走，
     *       「灰色拉链连帽卫衣」会被「卫衣」抢走；重叠的候选只保留最长那个；</li>
     *   <li>同一 productId 只保留一次（用户可能用两种说法指同一件）。</li>
     * </ol>
     *
     * <p>数量口径（有意的简化，别自作聪明扩展）：只有一个商品时用「买 3 件」的数量；
     * 多个商品时用「各 2 件」里的数量，没写就是 1 —— 避免"买 2 件卫衣和裤子"被理解成裤子也来 2 件。
     */
    public static List<PurchaseItem> purchaseItems(String message) {
        String value = message == null ? "" : message;
        if (!hasPurchaseIntent(value)) {
            return List.of();
        }
        return productItemsIn(value);
    }

    /**
     * **只**做"句子里提到了哪些商品"（不看有没有购买动作）。
     *
     * <p>为什么要拆：「把米色帆布双肩包加入购物车」里的"加入购物车"不是购买词，
     * 但确实点名了商品 —— 加购、比价这类动作需要商品实体，却不需要"下单"语义。
     */
    public static List<PurchaseItem> productItemsIn(String message) {
        String value = message == null ? "" : message;
        List<Span> spans = new ArrayList<>();
        Matcher idMatcher = PRODUCT_ID_PATTERN.matcher(value);
        while (idMatcher.find()) {
            spans.add(new Span(idMatcher.start(), idMatcher.end(), idMatcher.group(), idMatcher.group()));
        }
        for (Map.Entry<String, String> entry : PRODUCT_NAME_TO_ID.entrySet()) {
            int from = 0;
            while (true) {
                int index = value.indexOf(entry.getKey(), from);
                if (index < 0) {
                    break;
                }
                spans.add(new Span(index, index + entry.getKey().length(), entry.getKey(), entry.getValue()));
                from = index + 1;
            }
        }
        if (spans.isEmpty()) {
            return List.of();
        }
        // 位置升序；同位置长名优先；重叠（被长名覆盖的短名）直接丢掉
        spans.sort(Comparator.comparingInt(Span::start)
                .thenComparing((Span span) -> span.end() - span.start(), Comparator.reverseOrder()));
        List<Span> accepted = new ArrayList<>();
        for (Span candidate : spans) {
            boolean overlaps = accepted.stream()
                    .anyMatch(taken -> candidate.start() < taken.end() && taken.start() < candidate.end());
            if (!overlaps) {
                accepted.add(candidate);
            }
        }
        int quantity = quantityFor(value, accepted.size());
        List<PurchaseItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Span span : accepted) {
            if (seen.add(span.productId())) {
                items.add(new PurchaseItem(span.name(), span.productId(), quantity));
            }
        }
        return List.copyOf(items);
    }

    /** 单个商品沿用「买 3 件」；多个商品只认「各 2 件」，否则每个 1 件。 */
    private static int quantityFor(String message, int distinctItems) {
        if (distinctItems <= 1) {
            return extractPurchaseQuantity(message);
        }
        Matcher perItem = PER_ITEM_QUANTITY.matcher(message);
        return perItem.find() ? Math.max(1, Integer.parseInt(perItem.group(1))) : 1;
    }

    /** 句中一处商品提及（用于按位置去重叠）。 */
    private record Span(int start, int end, String name, String productId) {}

    /** 「买 3 件」里的数量；没写就是 1。 */
    public static int extractPurchaseQuantity(String message) {
        Matcher matcher = PURCHASE_QUANTITY.matcher(message == null ? "" : message);
        return matcher.find() ? Math.max(1, Integer.parseInt(matcher.group(1))) : 1;
    }

    /** 句子里能否定位到商品：先看商品编号，再按商品名称包含匹配。 */
    public static String productIdIn(String message) {
        String value = message == null ? "" : message;
        Matcher matcher = PRODUCT_ID_PATTERN.matcher(value);
        if (matcher.find()) {
            return matcher.group();
        }
        for (Map.Entry<String, String> entry : PRODUCT_NAME_TO_ID.entrySet()) {
            if (value.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 预算/选购类措辞（"最多""几件""预算""搭配""推荐"…），与明确下单互斥。 */
    public static boolean isBudgetQuestion(String message) {
        return containsAny(message == null ? "" : message, BUDGET_CUES);
    }

    // ===================== 2026-11-09：购物车 / 售后（原来只有路由、没有工具） =====================

    /**
     * 是否是**纯闲聊**（可以走直答快路径，不必先问模型"要不要工具"）。
     *
     * <p>判定刻意保守：短句 + 命中寒暄白名单 + **没有任何业务信号**
     * （{@link #needsAgentWorkflow} 为假）。最后一条很关键 —— 「你好，我要退货」这种
     * 带寒暄的业务请求必须进工作流，不能因为开头有"你好"就当成闲聊。
     */
    public static boolean isPureChitchat(String message) {
        String value = message == null ? "" : message.trim().toLowerCase();
        if (value.isEmpty()) {
            return true;
        }
        if (value.length() > CHITCHAT_MAX_LENGTH) {
            return false;
        }
        return containsAny(value, CHITCHAT_CUES) && !needsAgentWorkflow(value);
    }

    /**
     * 加购请求：「把黑色连帽卫衣加入购物车」「帮我加购物车」…
     *
     * <p>为什么单独抽出来：原来只有 {@link #isCartAction}（是不是购物车话题），
     * 没有任何工具，于是「加入购物车」被模型当成下单（还编了个不存在的商品编号）。
     */
    public static boolean isAddToCartRequest(String message) {
        String value = message == null ? "" : message;
        if (!value.contains("购物车")) {
            return false;
        }
        if (containsAny(value, CART_REMOVE_CUES)) {
            return false;
        }
        // 「加一件X到购物车」这种措辞不含固定短语，靠"购物车 + 添加动词"兜住
        return containsAny(value, CART_ADD_CUES)
                || containsAny(value, new String[]{"加", "放", "添加", "加入", "装"});
    }

    /** 结算购物车：「把购物车里的都下单」「购物车结算」。 */
    public static boolean isCartCheckoutRequest(String message) {
        String value = message == null ? "" : message;
        if (!value.contains("购物车")) {
            return false;
        }
        return containsAny(value, new String[]{"下单", "结算", "提交订单", "都买了", "全买了", "全下单"});
    }

    /**
     * 查看购物车（含"移除/清空"这类暂不支持的动作：先如实展示车里有什么）。
     *
     * <p>2026-11-09 收紧门槛：原来只要句子里出现"购物车"就当成查看命令，
     * 于是「购物车里的东西会自动清空吗」被答成"购物车现在是空的"（答非所问）。
     * 现在只认明确的查看意图（{@link #CART_VIEW_CUES}，或整句就是"购物车"/"我的购物车"），
     * 其余"购物车 + 规则/机制"的问法交给知识检索。
     */
    public static boolean isViewCartRequest(String message) {
        String value = message == null ? "" : message;
        if (!value.contains("购物车") || isAddToCartRequest(value)) {
            return false;
        }
        if (isRemoveFromCartRequest(value) || isCartCheckoutRequest(value)) {
            return true;   // 移除/清空：先展示车里有什么，再明说"对话里不支持"；结算有自己的分支
        }
        if (containsAny(value, CART_VIEW_CUES)) {
            return true;
        }
        // 「购物车」「我的购物车呢」这类整句：去掉标点与语气词后只剩购物车本身
        String bare = value.replaceAll("[\\s,，。.?？!！~]", "")
                .replace("帮我", "").replace("看一下", "").replace("看看", "")
                .replace("我的", "").replace("呢", "").replace("请", "");
        return bare.equals("购物车");
    }

    /** 退货**动作**（退货 ≠ 退款；知识问法不算）。 */
    public static boolean isReturnRequest(String message) {
        String value = message == null ? "" : message;
        if (!value.contains("退货") && !value.contains("换货")) {
            return false;
        }
        if (containsAny(value, RETURN_KNOWLEDGE_CUES) || containsAny(value, RETURN_STATUS_CUES)) {
            return false;
        }
        return containsAny(value, RETURN_ACTION_CUES);
    }

    /** 退货进度查询。 */
    public static boolean isReturnStatusQuery(String message) {
        String value = message == null ? "" : message;
        if (!value.contains("退货")) {
            return false;
        }
        return containsAny(value, RETURN_STATUS_CUES);
    }

    /** 退款进度查询：「我的退款到哪了」「退款申请状态」…（要查库，不是讲流程）。 */

    public static boolean isRefundStatusQuery(String message) {
        String value = message == null ? "" : message;
        if (!value.contains("退款")) {
            return false;
        }
        return containsAny(value, REFUND_STATUS_CUES);
    }

    /** 移除/清空购物车：对话里暂不支持，答案要明说。 */
    public static boolean isRemoveFromCartRequest(String message) {
        String value = message == null ? "" : message;
        return value.contains("购物车") && containsAny(value, CART_REMOVE_CUES);
    }

    /** 取消订单：「帮我取消订单」「把我的订单取消掉」。 */
    public static boolean isCancelOrderRequest(String message) {
        String value = message == null ? "" : message;
        if (isCancelNotOrderAction(value)) {
            return false;   // "取消订阅/取消关注" 之类
        }
        return containsAny(value, new String[]{"取消订单", "撤销订单", "订单取消"})
                || (value.contains("取消") && value.contains("订单"));
    }

    private static boolean isCancelNotOrderAction(String message) {
        return containsAny(message, new String[]{"取消订阅", "取消关注", "取消收藏", "取消优惠券"});
    }

    /**
     * 退款**动作**（不是"怎么退款"这种知识问法）。
     *
     * <p>区分很重要：知识问法应该走检索（答流程），动作问法要落退款申请单。
     */
    public static boolean isRefundRequest(String message) {
        String value = message == null ? "" : message;
        if (!value.contains("退款")) {
            return false;
        }
        if (containsAny(value, REFUND_KNOWLEDGE_CUES) || containsAny(value, REFUND_STATUS_CUES)) {
            return false;   // 知识问法答流程；进度问法去查库（QUERY_REFUND）
        }
        return containsAny(value, REFUND_ACTION_CUES);
    }

    /** 订单列表请求：「我有几个订单」「我的订单」「帮我查一下我的订单」… */
    public static boolean isOrderListRequest(String message) {
        String value = message == null ? "" : message;
        return containsAny(value, ORDER_LIST_CUES)
                || value.contains("一共") && value.contains("订单")
                || value.contains("买了") && value.contains("订单")
                || value.contains("展示") && value.contains("订单");
    }

    /** 购物车动作（加入/移除/清空）。 */
    public static boolean isCartAction(String message) {
        return containsAny(message == null ? "" : message, CART_CUES);
    }

    /** 账户/地址/支付方式等个人资料类问题。 */
    public static boolean isAccountQuery(String message) {
        return containsAny(message == null ? "" : message, ACCOUNT_CUES);
    }

    /** 订单生命周期动作（取消/收货/退款/改地址）。 */
    public static boolean isOrderLifecycleAction(String message) {
        return containsAny(message == null ? "" : message, ORDER_LIFECYCLE_CUES);
    }

    /** 建工单请求（动词 + 工单）。 */
    public static boolean isTicketCreateRequest(String message) {
        return TICKET_CREATE_PATTERN.matcher(message == null ? "" : message).find();
    }

    /** 工单动作（更新/关闭/升级/投诉）。 */
    public static boolean isTicketActionRequest(String message) {
        return containsAny(message == null ? "" : message, TICKET_ACTION_CUES);
    }

    /**
     * 是否属于工单类需求（意图判定口径）。
     *
     * <p>意图判定比路由宽一档：只要提到「工单」就算工单意图 —— 走到这一步已经在工作流里，
     * 模型漏判时补 {@code CREATE_TICKET} 比漏掉工单更安全。路由仍用 {@link #isTicketCreateRequest}。
     */
    public static boolean mentionsTicket(String message) {
        String value = message == null ? "" : message;
        return isTicketCreateRequest(value) || value.contains("工单");
    }

    /** 句子里是否出现了订单号（`ORD-1001`）。 */
    public static boolean hasOrderNo(String message) {
        return ORDER_NO_PATTERN.matcher(message == null ? "" : message).find();
    }

    // ===================== 检索与预算测算 =====================

    /**
     * 汇总型问题：需要"整张价格表"才能回答（预算多少能买几件、哪几件、怎么搭配、哪些便宜…）。
     *
     * <p>检索侧据此给汇总型片段（整张价格表）加分 —— 否则 328 字的汇总片段会被 57 字的单品片段压过去，
     * 模型只看得到少数几个单品，就会算出"最低价 ¥229"而漏掉真实最低价 ¥99。
     */
    public static boolean isAggregateQuestion(String message) {
        return containsAny(message == null ? "" : message, AGGREGATE_CUES);
    }

    /**
     * 是否是"预算内最多能买几件"这类**需要确定性测算**的问题。
     *
     * <p>判定为 true 时工作流会强制调用 {@code PLAN_BUDGET}：件数与合计必须由代码算，
     * 不能让模型自己加总（实测它会对同一问题给出 934 / 954 / 964 三个不同合计）。
     */
    public static boolean isBudgetCountingQuestion(String message) {
        String value = message == null ? "" : message;
        boolean hasCountCue = containsAny(value, "几件", "多少件", "几套", "能买", "最多", "可以买", "买得");
        return hasCountCue && extractBudgetAmount(value) != null;
    }

    /** 是否明确要求"不能重复购买同一款"。 */
    public static boolean forbidsRepeat(String message) {
        return Boolean.FALSE.equals(repeatPreference(message));
    }

    /**
     * 用户对"能否重复购买同一款"的表达。
     *
     * @return {@code FALSE} 明确不允许重复；{@code TRUE} 明确允许；{@code null} 没提 ——
     *         没提时按"默认可以重复"处理（"最多能买几件"的常规理解是数量最大化）。
     *
     * <p>为什么要区分"没提"和"明确允许"：模型会自己调 {@code PLAN_BUDGET} 并传 {@code allowRepeat=false}，
     * 导致"我有1000元，最多可以买几件衣服"被算成 5 件而不是 10 件。所以参数必须由这里定。
     */
    public static Boolean repeatPreference(String message) {
        String value = message == null ? "" : message;
        if (containsAny(value, "不能重复", "不重复", "不可重复", "各不相同", "每件不同", "不同的款", "不重样", "各一件")) {
            return Boolean.FALSE;
        }
        if (containsAny(value, "可以重复", "可重复", "重复购买", "同款多件", "多件同款", "一样的也行", "同款")) {
            return Boolean.TRUE;
        }
        return null;
    }

    /**
     * 从问题里抽出预算金额（单位：元）。
     *
     * <p>优先取"预算 xxx"这种显式写法；否则取形如 "1000元" 的数字。
     * 金额上限设 8 位，避免把订单号、商品编号里的数字当成预算。
     */
    public static Integer extractBudgetAmount(String message) {
        String value = message == null ? "" : message;
        Matcher explicit = Pattern.compile("预算\\s*(?:是|为|有|在)?\\s*(\\d{1,8})").matcher(value);
        if (explicit.find()) {
            return Integer.valueOf(explicit.group(1));
        }
        Matcher withYuan = Pattern.compile("(\\d{2,8})\\s*(?:元|块|rmb|RMB)").matcher(value);
        if (withYuan.find()) {
            return Integer.valueOf(withYuan.group(1));
        }
        return null;
    }

    /**
     * 给检索用的查询词。
     *
     * <p>「1000元预算可以怎么搭配」这种问法一个品类词都没有，直接拿原句做 BM25 会**一条都检不到**
     * （chunk 里只有"服装商品/价格"这类词），于是模型又回到"没有资料"。这里补上领域词提高召回。
     */
    public static String catalogQueryFor(String message) {
        String value = message == null ? "" : message;
        return hasCategoryWord(value) ? value : value + " 服装 商品 价格";
    }

    // ===================== 内部工具 =====================

    /**
     * 商品名称/说法 → productId。
     *
     * <p>**顺序即优先级**：先放 60 件商品的完整名称（V3 + V8 + V10 种子），再放通用品类词兜底。
     * 否则「我要买灰色拉链连帽卫衣」会被前面的「连帽卫衣 / 卫衣」抢先命中成黑色卫衣那一件。
     * 新增商品时三处一起加：`db/migration` 的 products、汇总价格表片段、这里的名称表
     * （`SeededProductCatalogConsistencyTest` 会检查前两处与这里是否对齐）。
     */
    private static Map<String, String> productIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        // —— 具体商品名（V3 种子 10 件）——
        ids.put("基础纯棉白色T恤", "CLOTH-TEE-001");
        ids.put("蓝色牛津纺衬衫", "CLOTH-SHIRT-002");
        ids.put("直筒水洗牛仔裤", "CLOTH-JEANS-003");
        ids.put("黑色连帽卫衣", "CLOTH-HOODIE-004");
        ids.put("碎花雪纺连衣裙", "CLOTH-DRESS-005");
        ids.put("轻薄防风夹克", "CLOTH-JACKET-006");
        ids.put("高腰A字半身裙", "CLOTH-SKIRT-007");
        ids.put("商务休闲Polo衫", "CLOTH-POLO-008");
        ids.put("羊毛混纺大衣", "CLOTH-COAT-009");
        ids.put("米色针织毛衣", "CLOTH-SWEATER-010");
        // —— 具体商品名（V8 新增 20 件）——
        ids.put("白色圆领短袖T恤", "CLOTH-TEE-011");
        ids.put("条纹短袖T恤", "CLOTH-TEE-012");
        ids.put("白色免烫衬衫", "CLOTH-SHIRT-013");
        ids.put("格纹法兰绒衬衫", "CLOTH-SHIRT-014");
        ids.put("灰色拉链连帽卫衣", "CLOTH-HOODIE-015");
        ids.put("燕麦色高领毛衣", "CLOTH-SWEATER-016");
        ids.put("深灰针织开衫", "CLOTH-CARDIGAN-017");
        ids.put("浅蓝阔腿牛仔裤", "CLOTH-JEANS-018");
        ids.put("黑色直筒休闲裤", "CLOTH-PANTS-019");
        ids.put("卡其五分短裤", "CLOTH-SHORTS-020");
        ids.put("军绿工装夹克", "CLOTH-JACKET-021");
        ids.put("米白风衣外套", "CLOTH-WINDBREAKER-022");
        ids.put("黑色短款羽绒服", "CLOTH-DOWN-023");
        ids.put("驼色双面呢大衣", "CLOTH-COAT-024");
        ids.put("法式方领连衣裙", "CLOTH-DRESS-025");
        ids.put("百褶中长半身裙", "CLOTH-SKIRT-026");
        ids.put("白色厚底运动鞋", "CLOTH-SNEAKER-027");
        ids.put("藏青帆布鞋", "CLOTH-CANVAS-028");
        ids.put("米色帆布双肩包", "CLOTH-BAG-029");
        ids.put("灰色羊毛围巾", "CLOTH-SCARF-030");
        // —— 具体商品名（V10 新增 30 件，目录 30 → 60）——
        ids.put("男士亚麻短袖衬衫", "CLOTH-SHIRT-031");
        ids.put("女士雪纺系带衬衫", "CLOTH-SHIRT-032");
        ids.put("重磅纯棉宽松T恤", "CLOTH-TEE-033");
        ids.put("复古印花短袖T恤", "CLOTH-TEE-034");
        ids.put("男式圆领羊绒衫", "CLOTH-SWEATER-035");
        ids.put("马海毛宽松毛衣", "CLOTH-SWEATER-036");
        ids.put("儿童卡通连帽卫衣", "CLOTH-HOODIE-037");
        ids.put("复古牛仔夹克", "CLOTH-JACKET-038");
        ids.put("三防冲锋衣", "CLOTH-JACKET-039");
        ids.put("双排扣羊毛大衣", "CLOTH-COAT-040");
        ids.put("短款毛呢外套", "CLOTH-COAT-041");
        ids.put("高腰西装直筒裤", "CLOTH-PANTS-042");
        ids.put("运动束脚休闲裤", "CLOTH-PANTS-043");
        ids.put("复古水洗直筒牛仔裤", "CLOTH-JEANS-044");
        ids.put("牛仔五分短裤", "CLOTH-SHORTS-045");
        ids.put("牛仔A字半身裙", "CLOTH-SKIRT-046");
        ids.put("蕾丝中长半身裙", "CLOTH-SKIRT-047");
        ids.put("针织连衣长裙", "CLOTH-DRESS-048");
        ids.put("通勤西装连衣裙", "CLOTH-DRESS-049");
        ids.put("纯棉家居服套装", "CLOTH-PAJAMA-050");
        ids.put("法兰绒睡袍", "CLOTH-PAJAMA-051");
        ids.put("速干运动T恤", "CLOTH-SPORT-052");
        ids.put("高弹瑜伽裤", "CLOTH-SPORT-053");
        ids.put("连体泳衣", "CLOTH-SWIM-054");
        ids.put("切尔西短靴", "CLOTH-BOOTS-055");
        ids.put("细高跟鞋", "CLOTH-HEEL-056");
        ids.put("平底凉鞋", "CLOTH-SANDAL-057");
        ids.put("针织毛线帽", "CLOTH-HAT-058");
        ids.put("加绒保暖手套", "CLOTH-GLOVES-059");
        ids.put("偏光太阳镜", "CLOTH-SUNGLASS-060");
        // —— 通用品类词兜底（没点名具体商品时用该类目的默认款）——
        ids.put("白色T恤", "CLOTH-TEE-001");
        ids.put("纯棉T恤", "CLOTH-TEE-001");
        ids.put("T恤", "CLOTH-TEE-001");
        ids.put("牛津纺衬衫", "CLOTH-SHIRT-002");
        ids.put("蓝色衬衫", "CLOTH-SHIRT-002");
        ids.put("免烫衬衫", "CLOTH-SHIRT-013");
        ids.put("法兰绒衬衫", "CLOTH-SHIRT-014");
        ids.put("衬衫", "CLOTH-SHIRT-002");
        ids.put("水洗牛仔裤", "CLOTH-JEANS-003");
        ids.put("直筒牛仔裤", "CLOTH-JEANS-003");
        ids.put("阔腿牛仔裤", "CLOTH-JEANS-018");
        ids.put("牛仔裤", "CLOTH-JEANS-003");
        ids.put("连帽卫衣", "CLOTH-HOODIE-004");
        ids.put("黑色卫衣", "CLOTH-HOODIE-004");
        ids.put("拉链卫衣", "CLOTH-HOODIE-015");
        ids.put("卫衣", "CLOTH-HOODIE-004");
        ids.put("雪纺连衣裙", "CLOTH-DRESS-005");
        ids.put("碎花连衣裙", "CLOTH-DRESS-005");
        ids.put("方领连衣裙", "CLOTH-DRESS-025");
        ids.put("连衣裙", "CLOTH-DRESS-005");
        ids.put("防风夹克", "CLOTH-JACKET-006");
        ids.put("轻薄夹克", "CLOTH-JACKET-006");
        ids.put("工装夹克", "CLOTH-JACKET-021");
        ids.put("夹克", "CLOTH-JACKET-006");
        ids.put("A字半身裙", "CLOTH-SKIRT-007");
        ids.put("百褶裙", "CLOTH-SKIRT-026");
        ids.put("半身裙", "CLOTH-SKIRT-007");
        ids.put("Polo衫", "CLOTH-POLO-008");
        ids.put("POLO衫", "CLOTH-POLO-008");
        ids.put("polo衫", "CLOTH-POLO-008");
        ids.put("羊毛混纺大衣", "CLOTH-COAT-009");
        ids.put("羊毛大衣", "CLOTH-COAT-009");
        ids.put("双面呢大衣", "CLOTH-COAT-024");
        ids.put("大衣", "CLOTH-COAT-009");
        ids.put("针织毛衣", "CLOTH-SWEATER-010");
        ids.put("米色毛衣", "CLOTH-SWEATER-010");
        ids.put("高领毛衣", "CLOTH-SWEATER-016");
        ids.put("毛衣", "CLOTH-SWEATER-010");
        ids.put("开衫", "CLOTH-CARDIGAN-017");
        ids.put("休闲裤", "CLOTH-PANTS-019");
        ids.put("长裤", "CLOTH-PANTS-019");
        ids.put("裤子", "CLOTH-PANTS-019");
        ids.put("短裤", "CLOTH-SHORTS-020");
        ids.put("风衣", "CLOTH-WINDBREAKER-022");
        ids.put("羽绒服", "CLOTH-DOWN-023");
        ids.put("运动鞋", "CLOTH-SNEAKER-027");
        ids.put("帆布鞋", "CLOTH-CANVAS-028");
        ids.put("双肩包", "CLOTH-BAG-029");
        ids.put("围巾", "CLOTH-SCARF-030");
        return Collections.unmodifiableMap(ids);
    }

    private static boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 信号 → 消费者清单（**改表前看这张表就知道会波及谁**）。
     *
     * <p>维护约定：本类每新增一个 public 信号方法，**必须**在这里登记它的消费者，
     * 否则 {@code QuestionSignalsImpactTest} 会失败（用反射对账）。消费者写的是"类.方法"级别的具体位置，
     * 不是抽象角色 —— 出问题时能直接顺着找过去。
     */
    static Map<String, List<String>> signalConsumers() {
        Map<String, List<String>> consumers = new LinkedHashMap<>();
        consumers.put("needsAgentWorkflow", List.of(
                "AssistantWorkflowService.shouldUseAgentWorkflow（路由）"));
        consumers.put("orderNoIn", List.of(
                "AssistantWorkflowService（请求没带 orderNo 时从消息里抽，取消/退款/查询都要用）"));
        consumers.put("hasOrderNo", List.of(
                "AssistantWorkflowService（路由）"));
        consumers.put("isCatalogOrBudgetQuestion", List.of(
                "AssistantWorkflowService（路由）",
                "IntentAgent.applyCatalogGuard / heuristicToolCalls（补检索）",
                "FallbackAiClientAdapter（兜底 JSON）",
                "Bm25SearchAdapter（汇总片段加权）"));
        consumers.put("hasCategoryWord", List.of(
                "FallbackAiClientAdapter.isBudgetCatalogQuestion（兜底预算判定）",
                "QuestionSignals.catalogQueryFor（检索查询词）"));
        consumers.put("hasPurchaseIntent", List.of(
                "AssistantWorkflowService（路由）",
                "FallbackAiClientAdapter（兜底 CREATE_ORDER）"));
        consumers.put("purchaseProductId", List.of(
                "IntentAgent.heuristicIntent（判定「这是明确下单」）"));
        consumers.put("isPureChitchat", List.of(
                "AssistantWorkflowService.runWorkflow（闲聊快路径：不进模型意图调用）"));
        consumers.put("productItemsIn", List.of(
                "IntentAgent.heuristicToolCalls（加购：不要求购买动作，只要求点名商品）"));
        consumers.put("purchaseItems", List.of(
                "IntentAgent.purchaseToolCalls（一句话多件商品 → 多个 CREATE_ORDER）",
                "FallbackAiClientAdapter（兜底下单 JSON）"));
        consumers.put("extractPurchaseQuantity", List.of(
                "IntentAgent（下单数量）",
                "FallbackAiClientAdapter（兜底下单数量）"));
        consumers.put("productIdIn", List.of(
                "IntentAgent（从文本里取商品编号）"));
        consumers.put("isBudgetQuestion", List.of(
                "IntentAgent.applyBudgetPlanGuard（与下单互斥）"));
        consumers.put("isOrderListRequest", List.of(
                "AssistantWorkflowService（路由）",
                "IntentAgent.heuristicIntent",
                "FallbackAiClientAdapter（兜底订单列表）",
                "QueryOrderTool（列表 vs 单订单）"));
        consumers.put("isCartCheckoutRequest", List.of(
                "IntentAgent.heuristicToolCalls（→ CREATE_ORDER{fromCart:true}，下单成功后清空购物车）"));
        consumers.put("isAddToCartRequest", List.of(
                "IntentAgent.heuristicToolCalls（→ ADD_TO_CART，确定性，不靠模型选）"));
        consumers.put("isViewCartRequest", List.of(
                "IntentAgent.heuristicToolCalls（→ VIEW_CART）"));
        consumers.put("isRemoveFromCartRequest", List.of(
                "ViewCartTool（对话里不支持移除，答案要明说）"));
        consumers.put("isCancelOrderRequest", List.of(
                "IntentAgent.heuristicToolCalls（→ CANCEL_ORDER）"));
        consumers.put("isReturnRequest", List.of(
                "IntentAgent.afterSalesToolCalls（→ REQUEST_RETURN）"));
        consumers.put("isReturnStatusQuery", List.of(
                "IntentAgent.afterSalesToolCalls（→ QUERY_RETURN）"));
        consumers.put("isRefundStatusQuery", List.of(
                "IntentAgent.afterSalesToolCalls（→ QUERY_REFUND）"));
        consumers.put("isRefundRequest", List.of(
                "IntentAgent.heuristicToolCalls（→ REQUEST_REFUND；知识问法不算，走检索）"));        consumers.put("isCartAction", List.of(
                "AssistantWorkflowService（路由）"));
        consumers.put("isAccountQuery", List.of(
                "AssistantWorkflowService（路由）"));
        consumers.put("isOrderLifecycleAction", List.of(
                "AssistantWorkflowService（路由）"));
        consumers.put("isTicketCreateRequest", List.of(
                "AssistantWorkflowService（路由）"));
        consumers.put("isTicketActionRequest", List.of(
                "AssistantWorkflowService（路由）"));
        consumers.put("mentionsTicket", List.of(
                "IntentAgent.heuristicIntent（工单类兜底）"));
        consumers.put("isAggregateQuestion", List.of(
                "Bm25SearchAdapter（汇总片段加权）"));
        consumers.put("isBudgetCountingQuestion", List.of(
                "IntentAgent.applyBudgetPlanGuard（强制 PLAN_BUDGET）",
                "FallbackAiClientAdapter.isBudgetCatalogQuestion（兜底）"));
        consumers.put("forbidsRepeat", List.of(
                "PlanBudgetTool（allowRepeat 参数）"));
        consumers.put("repeatPreference", List.of(
                "IntentAgent（PLAN_BUDGET 参数）",
                "PlanBudgetTool（allowRepeat 参数）"));
        consumers.put("extractBudgetAmount", List.of(
                "IntentAgent（PLAN_BUDGET 参数）",
                "FallbackAiClientAdapter（兜底预算）",
                "PlanBudgetTool（预算金额）"));
        consumers.put("catalogQueryFor", List.of(
                "IntentAgent（SEARCH_KNOWLEDGE 查询词）"));
        return Collections.unmodifiableMap(consumers);
    }

    /**
     * 仅供影响面测试：把每张关键词表摊平成「表名 → 词条」。
     *
     * <p>这样测试**不必复制一份关键词**（复制出去的表迟早与真实表不一致），
     * 表里加一个词，影响面矩阵就会多一行，改坏了立刻在 diff 里看得见。
     */
    static Map<String, List<String>> cueTables() {
        Map<String, List<String>> tables = new LinkedHashMap<>();
        tables.put("CATEGORY", List.of(CATEGORY));
        tables.put("CATALOG_CUES", List.of(CATALOG_CUES));
        tables.put("SHOPPING_CUES", List.of(SHOPPING_CUES));
        tables.put("MONEY_OR_QUANTITY", List.of(MONEY_OR_QUANTITY));
        tables.put("AGGREGATE_CUES", List.of(AGGREGATE_CUES));
        tables.put("BUDGET_CUES", List.of(BUDGET_CUES));
        tables.put("PURCHASE_CUES", List.of(PURCHASE_CUES));
        tables.put("CART_CUES", List.of(CART_CUES));
        tables.put("ORDER_LIST_CUES", List.of(ORDER_LIST_CUES));
        tables.put("ACCOUNT_CUES", List.of(ACCOUNT_CUES));
        tables.put("ORDER_LIFECYCLE_CUES", List.of(ORDER_LIFECYCLE_CUES));
        tables.put("TICKET_ACTION_CUES", List.of(TICKET_ACTION_CUES));
        tables.put("PRODUCT_NAME_TO_ID", List.copyOf(PRODUCT_NAME_TO_ID.keySet()));
        return Collections.unmodifiableMap(tables);
    }
}
