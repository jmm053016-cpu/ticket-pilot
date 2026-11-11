package com.example.aiticketassistant.application.agent;

import com.example.aiticketassistant.application.catalog.CategoryCandidateService;
import com.example.aiticketassistant.application.conversation.ConversationTurn;
import com.example.aiticketassistant.application.order.OrderReferenceResolver;
import com.example.aiticketassistant.application.prompt.PromptCatalog;
import com.example.aiticketassistant.application.prompt.PromptIds;
import com.example.aiticketassistant.application.prompt.PromptTemplate;
import com.example.aiticketassistant.application.routing.PurchaseItem;
import com.example.aiticketassistant.application.routing.QuestionSignals;
import com.example.aiticketassistant.application.tool.ToolCall;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.ticket.TicketPriority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IntentAgent {
    private static final Logger log = LoggerFactory.getLogger(IntentAgent.class);

    /** 上下文里"品类候选"的键（工作流据此渲染候选 + 反问，而不是直接下单）。 */
    public static final String PURCHASE_CANDIDATES_KEY = "purchaseCandidates";

    private final AiClientPort aiClient;
    private final ObjectMapper objectMapper;
    private final PromptCatalog promptCatalog;
    private final CategoryCandidateService categoryCandidates;
    private final OrderReferenceResolver orderReferenceResolver;
    /** 是否"模型优先"：真模型默认 true（模型决定调哪个工具），规则兜底引擎默认 false（走关键词层）。 */
    private final boolean modelFirst;

    public IntentAgent(AiClientPort aiClient,
                       ObjectMapper objectMapper,
                       PromptCatalog promptCatalog,
                       @Value("${assistant.ai.provider:fallback}") String provider,
                       @Value("${assistant.intent.mode:auto}") String intentMode,
                       CategoryCandidateService categoryCandidates,
                       OrderReferenceResolver orderReferenceResolver) {
        this.aiClient = aiClient;
        this.objectMapper = objectMapper;
        this.promptCatalog = promptCatalog;
        this.categoryCandidates = categoryCandidates;
        this.orderReferenceResolver = orderReferenceResolver;
        this.modelFirst = resolveModelFirst(provider, intentMode);
        log.info("IntentAgent 决策模式: {}（provider={}, assistant.intent.mode={}）",
                this.modelFirst ? "MODEL_FIRST（模型决定工具，关键词兜底）" : "LEGACY（关键词优先，模型只补措辞）",
                provider, intentMode);
    }

    /**
     * 解析决策模式。
     *
     * <ul>
     *   <li>{@code legacy}：旧的"关键词覆盖优先"，保留用于 A/B 对比与回滚。</li>
     *   <li>{@code model-first}：强制模型优先（即使 provider 是 fallback）。</li>
     *   <li>{@code auto}（默认）：真模型 → 模型优先；{@code fallback} 规则引擎 → 沿用关键词层
     *       （它不是"理解"，只是模板匹配，让它决定工具反而不如关键词确定）。</li>
     * </ul>
     */
    private static boolean resolveModelFirst(String provider, String intentMode) {
        String mode = intentMode == null ? "auto" : intentMode.trim().toLowerCase().replace("_", "-");
        if ("legacy".equals(mode)) {
            return false;
        }
        if ("model-first".equals(mode) || "modelfirst".equals(mode)) {
            return true;
        }
        return provider != null && !"fallback".equalsIgnoreCase(provider.trim());
    }

    /** 仅用于测试与诊断：当前是否模型优先。 */
    boolean modelFirst() {
        return modelFirst;
    }

    public IntentResult analyze(WorkflowContext context, String toolMetadata) {
        PromptTemplate prompt = promptCatalog.get(PromptIds.INTENT_AGENT);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("userMessage", context.userMessage());
        variables.put("customerId", context.customerId());
        variables.put("orderNo", context.orderNo());
        variables.put("toolMetadata", toolMetadata);
        variables.put("history", ConversationTurn.toPromptBlock(context.conversationHistory()));
        AiResponse response = aiClient.complete(new AiRequest(
                "IntentAgent",
                prompt.system(),
                prompt.user(variables),
                Map.of("customerId", context.customerId(), "orderNo", context.orderNo() == null ? "" : context.orderNo(),
                        "userMessage", context.userMessage()),
                true));
        return parseOrFallback(response.text(), context);
    }

    private IntentResult parseOrFallback(String json, WorkflowContext context) {
        try {
            JsonNode root = objectMapper.readTree(json);
            IntentType intent = parseIntent(root.path("intent").asText(""));
            TicketPriority priority = TicketPriority.normalize(root.path("priority").asText("MEDIUM"));
            String title = root.path("title").asText(defaultTitle(context.userMessage()));
            double confidence = root.path("confidence").asDouble(0.6);
            // 只说了品类（「我要买一件羽绒服」）：**不替用户挑**，记住候选让工作流列 2~3 个 + 反问。
            // 判据在 CategoryCandidateService 里：该品类在目录里有多个 SKU 才算歧义，只有一个就照常下单。
            if (isPurchaseOrCartAction(context.userMessage())) {
                CategoryCandidateService.Candidates candidates =
                        categoryCandidates.findCandidates(context.userMessage()).orElse(null);
                if (candidates != null && candidates.candidates().size() >= 2) {
                    context.remember(PURCHASE_CANDIDATES_KEY, candidates.toMap());
                    log.info("「{}」只给了品类「{}」，目录里有 {} 个 SKU → 列候选而不是下单",
                            context.userMessage(), candidates.category(), candidates.totalInCategory());
                    return new IntentResult(intent == null ? IntentType.GENERAL_SUPPORT : intent, title, priority,
                            Math.max(confidence, 0.8), List.of());
                }
            }
            List<ToolCall> toolCalls = new ArrayList<>();
            JsonNode calls = root.path("tool_calls");
            if (calls.isArray()) {
                for (JsonNode call : calls) {
                    String toolName = call.path("tool").asText();
                    if (!ToolNames.isAiCallable(toolName)) {
                        log.warn("IntentAgent ignored non-callable tool '{}' in model output", toolName);
                        continue;
                    }
                    toolCalls.add(new ToolCall(toolName, objectMapper.convertValue(call.path("arguments"), Map.class)));
                }
            }
            // ===== 模型优先（真模型默认）=====
            // 让模型自己决定调哪个工具（提示词里给了完整工具清单与选择口径）。
            // 关键词层只在"模型没给任何工具调用"时兜底 —— 2026-11-11 之前是反过来的
            // （关键词覆盖优先），于是「要加购」被代码判成「下单」，模型根本没有发言权。
            if (modelFirst && !toolCalls.isEmpty()) {
                IntentType resolvedIntent = intent == null ? heuristicIntent(context.userMessage()) : intent;
                List<ToolCall> enriched = enrichArguments(toolCalls, context);
                List<ToolCall> guarded = applyBudgetPlanGuard(
                        applyCatalogGuard(resolvedIntent, enriched, context), context);
                return new IntentResult(resolvedIntent, title, priority, Math.max(confidence, 0.8), guarded);
            }

            // ===== 兜底：规则引擎（offline）或模型没给工具 =====
            // 顺序：购物车三态 > 下单 > 售后/账户 > 意图启发式
            List<ToolCall> cartCalls = cartToolCalls(context.userMessage(), context, title);
            if (!cartCalls.isEmpty()) {
                return new IntentResult(intent == null ? IntentType.GENERAL_SUPPORT : intent, title, priority,
                        Math.max(confidence, 0.9), cartCalls);
            }
            List<ToolCall> purchaseCalls = purchaseToolCalls(context.userMessage());
            if (!purchaseCalls.isEmpty()) {
                return new IntentResult(IntentType.GENERAL_SUPPORT, "安全下单确认", priority, Math.max(confidence, 0.9), purchaseCalls);
            }
            List<ToolCall> afterSalesCalls = afterSalesToolCalls(context.userMessage(), context, title);
            if (!afterSalesCalls.isEmpty()) {
                return new IntentResult(intent == null ? IntentType.GENERAL_SUPPORT : intent, title, priority,
                        Math.max(confidence, 0.9), afterSalesCalls);
            }
            intent = intent == null ? heuristicIntent(context.userMessage()) : intent;
            toolCalls = heuristicToolCalls(intent, title, priority, context);
            // 模型返回枚举外意图时 intent 可能仍为 null，这里兜底，避免 NPE 且不丢 title/confidence/tool_calls
            intent = intent == null ? heuristicIntent(context.userMessage()) : intent;
            toolCalls = applyBudgetPlanGuard(applyCatalogGuard(intent, toolCalls, context), context);
            return new IntentResult(intent, title, priority, confidence, toolCalls);
        } catch (Exception ex) {
            log.warn("IntentAgent JSON parse failed, falling back to heuristics. raw={}", json, ex);
            IntentType intent = heuristicIntent(context.userMessage());
            TicketPriority priority = context.userMessage().contains("失败") || context.userMessage().contains("投诉")
                    ? TicketPriority.HIGH : TicketPriority.MEDIUM;
            String title = defaultTitle(context.userMessage());
            List<ToolCall> toolCalls = applyBudgetPlanGuard(
                    applyCatalogGuard(intent, heuristicToolCalls(intent, title, priority, context), context), context);
            return new IntentResult(intent, title, priority, 0.55, toolCalls);
        }
    }

    /**
     * 商品/预算类问题的**确定性兜底**：模型漏调检索时补一条 {@code SEARCH_KNOWLEDGE}。
     *
     * <p>为什么需要：要不要检索原本完全由模型决定，同一个需求换个说法就可能不检索，
     * 于是模型手里没有任何商品数据，只能凭空编（"上装300、下装250、鞋350"）或反问用户单价。
     * 实测「1000元预算可以怎么搭配」「我有1000元最多可以买几件衣服」都漏了检索。
     *
     * <p>不补的四种情形：已经检索过；属于下单流程（有 {@code CREATE_ORDER}，避免干扰确认卡片）；
     * 意图与商品无关（订单查询/建工单等）；这句话不像在问商品或预算。
     */
    private List<ToolCall> applyCatalogGuard(IntentType intent, List<ToolCall> toolCalls, WorkflowContext context) {
        String message = context.userMessage();
        boolean catalogRelatedIntent = intent == IntentType.GENERAL_SUPPORT
                || intent == IntentType.KNOWLEDGE_QA
                || intent == IntentType.ORDER_AND_KNOWLEDGE;
        if (message == null
                || message.isBlank()
                || !catalogRelatedIntent
                || hasTool(toolCalls, ToolNames.SEARCH_KNOWLEDGE)
                || hasTool(toolCalls, ToolNames.CREATE_ORDER)
                || !QuestionSignals.isCatalogOrBudgetQuestion(message)) {
            return toolCalls;
        }
        log.info("IntentAgent 补充 SEARCH_KNOWLEDGE（模型未主动检索商品/预算类问题）：{}", message);
        List<ToolCall> guarded = new ArrayList<>(toolCalls);
        guarded.add(new ToolCall(ToolNames.SEARCH_KNOWLEDGE, Map.of("query", QuestionSignals.catalogQueryFor(message), "limit", 5)));
        return guarded;
    }

    /**
     * 「预算内最多能买几件」这类问题强制调用 {@code PLAN_BUDGET}，让件数与合计由代码算。
     *
     * <p>为什么必须强制：实测模型对同一问题会给出 934 / 954 / 964 三个不同的合计（真值 1144），
     * 而且同一段回答里"结论 6 件"与"明细表合计 1144"互相矛盾；在干净会话（无历史）里必错，
     * 有历史时只是因为上文里恰好有正确答案才复述对。正确性不能建立在"碰巧"上。
     *
     * <p>预算金额解析不出来时不追加——没有预算就没法测算，交给模型反问。
     */
    private List<ToolCall> applyBudgetPlanGuard(List<ToolCall> toolCalls, WorkflowContext context) {
        String message = context.userMessage();
        if (message == null
                || message.isBlank()
                || hasTool(toolCalls, ToolNames.CREATE_ORDER)
                || !QuestionSignals.isBudgetCountingQuestion(message)) {
            return toolCalls;
        }
        Integer budget = QuestionSignals.extractBudgetAmount(message);
        if (budget == null || budget <= 0) {
            return toolCalls;
        }
        // 参数一律由代码定：模型会自己调 PLAN_BUDGET 但把 allowRepeat 传错
        // （实测「我有1000元，最多可以买几件衣服」被传成 false，算成 5 件而不是 10 件）。
        Boolean repeatPreference = QuestionSignals.repeatPreference(message);
        boolean allowRepeat = repeatPreference == null || repeatPreference;
        List<ToolCall> guarded = new ArrayList<>();
        for (ToolCall call : toolCalls) {
            if (!ToolNames.PLAN_BUDGET.equals(call.tool())) {
                guarded.add(call);
            }
        }
        log.info("IntentAgent 追加 PLAN_BUDGET（预算测算交给代码）：budget={} allowRepeat={}", budget, allowRepeat);
        guarded.add(new ToolCall(ToolNames.PLAN_BUDGET, Map.of("budget", budget, "allowRepeat", allowRepeat)));
        return guarded;
    }


    private boolean hasTool(List<ToolCall> toolCalls, String toolName) {
        return toolCalls.stream().anyMatch(call -> toolName.equals(call.tool()));
    }

    /**
     * 容错解析意图值。
     *
     * <p>真实模型偶尔会返回枚举外的值 —— 实测 DeepSeek 对「我要买一件黑色卫衣」返回
     * {@code {"intent":"CREATE_ORDER"}}（把工具名当成意图）。原来直接 {@code valueOf} 会抛异常，
     * 整段 JSON 作废、连 title/confidence/tool_calls 一起丢掉，只剩启发式兜底。
     * 现在改为返回 null 由调用方按启发式补判，模型给出的其余字段仍然保留。
     */
    private IntentType parseIntent(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return IntentType.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("IntentAgent 收到枚举外的意图 '{}'，按启发式补判", raw);
            return null;
        }
    }

    /**
     * 模型没给出可用意图时的启发式兜底。
     *
     * <p>每一类判定都走 {@link QuestionSignals}（唯一口径），这里只负责"谁优先"的顺序：
     * 订单列表 &gt; 明确下单 &gt; 商品/预算问答 &gt; 工单 &gt; 订单+知识 &gt; 订单查询 &gt; 知识问答 &gt; 通用。
     */
    private IntentType heuristicIntent(String message) {
        String value = message == null ? "" : message;
        if (QuestionSignals.isOrderListRequest(value)) {
            return IntentType.QUERY_ORDER;
        }
        if (QuestionSignals.purchaseProductId(value) != null) {
            return IntentType.GENERAL_SUPPORT;
        }
        if (QuestionSignals.isCatalogOrBudgetQuestion(value)) {
            return IntentType.KNOWLEDGE_QA;
        }
        if (QuestionSignals.mentionsTicket(value)) {
            return IntentType.CREATE_TICKET;
        }
        if (value.contains("订单") && (value.contains("怎么办") || value.contains("知识") || value.contains("没生成"))) {
            return IntentType.ORDER_AND_KNOWLEDGE;
        }
        if (value.contains("订单")) {
            return IntentType.QUERY_ORDER;
        }
        if (value.contains("怎么办") || value.contains("FAQ")) {
            return IntentType.KNOWLEDGE_QA;
        }
        return IntentType.GENERAL_SUPPORT;
    }

    private List<ToolCall> heuristicToolCalls(IntentType intent, String title, TicketPriority priority, WorkflowContext context) {
        String message = context.userMessage();
        List<ToolCall> calls = new ArrayList<>();
        if (QuestionSignals.isOrderListRequest(message)) {
            calls.add(new ToolCall(ToolNames.QUERY_ORDER, Map.of("customerId", context.customerId())));
            return calls;
        }
        List<ToolCall> cartCalls = cartToolCalls(message, context, title);
        if (!cartCalls.isEmpty()) {
            return cartCalls;
        }
        List<ToolCall> purchaseCalls = purchaseToolCalls(message);
        if (!purchaseCalls.isEmpty()) {
            return purchaseCalls;
        }
        // 2026-11-09：购物车 / 售后 / 账户走**确定性工具**（见 afterSalesToolCalls）
        List<ToolCall> afterSalesCalls = afterSalesToolCalls(message, context, title);
        if (!afterSalesCalls.isEmpty()) {
            return afterSalesCalls;
        }
        if (QuestionSignals.isCatalogOrBudgetQuestion(message)) {
            // 查询词同样走统一口径：句子里没有品类词时补领域词，否则 BM25 一条都检不到。
            calls.add(new ToolCall(ToolNames.SEARCH_KNOWLEDGE, Map.of(
                    "query", QuestionSignals.catalogQueryFor(message),
                    "limit", 5)));
            return calls;
        }
        if (intent == IntentType.CREATE_TICKET) {
            calls.add(new ToolCall(ToolNames.CREATE_TICKET, Map.of(
                    "customerId", context.customerId(),
                    "title", title,
                    "description", message,
                    "priority", priority.name())));
        }
        if (intent == IntentType.QUERY_ORDER || intent == IntentType.ORDER_AND_KNOWLEDGE) {
            calls.add(new ToolCall(ToolNames.QUERY_ORDER, Map.of(
                    "customerId", context.customerId(),
                    "orderNo", context.orderNo() == null ? "" : context.orderNo())));
        }
        if (intent == IntentType.KNOWLEDGE_QA || intent == IntentType.ORDER_AND_KNOWLEDGE || message.contains("怎么办")) {
            calls.add(new ToolCall(ToolNames.SEARCH_KNOWLEDGE, Map.of(
                    "query", message,
                    "limit", 5)));
        }
        return calls;
    }

    /**
     * 给模型给出的工具调用**补参数**（不是替换动作）。
     *
     * <p>为什么需要：模型擅长判断"该做什么"，但不该也不可能知道我们的商品编号。
     * 提示词要求它"不确定就留空"，这里用确定性数据补上：
     * 商品来自 {@code QuestionSignals.productItemsIn}，订单号来自上下文（已从消息里抽过），
     * 预算金额来自 {@code extractBudgetAmount}。这样既保留模型的动作判断，又不会让它编 id。
     */
    private List<ToolCall> enrichArguments(List<ToolCall> calls, WorkflowContext context) {
        List<ToolCall> enriched = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            Map<String, Object> arguments = call.arguments() == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(call.arguments());
            switch (call.tool()) {
                case ToolNames.CREATE_ORDER -> fillCartItems(arguments, context.userMessage(), false);
                case ToolNames.ADD_TO_CART -> fillCartItems(arguments, context.userMessage(), true);
                case ToolNames.CANCEL_ORDER, ToolNames.REQUEST_REFUND, ToolNames.REQUEST_RETURN,
                     ToolNames.QUERY_REFUND, ToolNames.QUERY_RETURN, ToolNames.QUERY_ORDER ->
                        fillOrderNo(arguments, context, call.tool());
                case ToolNames.PLAN_BUDGET -> fillBudget(arguments, context.userMessage());
                case ToolNames.SEARCH_KNOWLEDGE -> fillQuery(arguments, context.userMessage());
                default -> { /* 其余工具不需要补参数 */ }
            }
            enriched.add(new ToolCall(call.tool(), arguments));
        }
        return enriched;
    }

    /**
     * 补商品明细：模型只负责"要加哪几件"，**商品编号必须由我们填**。
     *
     * <p>真机踩过（2026-11-11）：「我想要买军绿工装夹克、轻薄防风夹克、复古牛仔夹克，先帮我加入到购物车」，
     * 模型正确地选了 {@code ADD_TO_CART}，但 items 是三条 `{productId:"", quantity:1}` ——
     * 提示词要求"不知道就留空"，它照做了。旧版把"items 非空"当成"已经给了明细"直接跳过补参，
     * 于是护栏报"商品编号不能为空"。
     * 现在的判据是：**每一条都必须有非空 productId**，否则整组按句子里点名的商品重建
     * （模型给的数量按顺序保留，没给就用句子里的数量）。
     */
    private void fillCartItems(Map<String, Object> arguments, String message, boolean cart) {
        Object fromCart = arguments.get("fromCart");
        boolean checkoutFromCart = fromCart != null && Boolean.parseBoolean(String.valueOf(fromCart));
        if (checkoutFromCart) {
            return;   // 整车结算：明细来自购物车表，不经模型
        }
        Object rawItems = arguments.get("items");
        List<?> modelItems = rawItems instanceof List<?> list ? list : List.of();
        boolean everyItemHasId = !modelItems.isEmpty() && modelItems.stream().allMatch(IntentAgent::hasProductId);
        boolean singleHasId = arguments.get("productId") instanceof String productId && !productId.isBlank();
        if (everyItemHasId || singleHasId) {
            return;   // 模型给全了，不动
        }

        List<PurchaseItem> mentioned = QuestionSignals.productItemsIn(message);
        if (mentioned.isEmpty()) {
            return;   // 句子里也没识别出商品 → 让护栏报一句人话（"请说明具体商品"）
        }

        List<Map<String, Object>> filled = new ArrayList<>(mentioned.size());
        for (int index = 0; index < mentioned.size(); index++) {
            PurchaseItem item = mentioned.get(index);
            filled.add(Map.of(
                    "productId", item.productId(),
                    "quantity", quantityAt(modelItems, index, item.quantity())));
        }
        arguments.remove("productId");
        arguments.remove("quantity");
        arguments.put("items", filled);
    }

    /** 单条 item 是否有非空 productId。 */
    private static boolean hasProductId(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            return false;
        }
        Object productId = map.get("productId");
        return productId != null && !String.valueOf(productId).isBlank();
    }

    /** 模型给的第 index 条数量（没有就退回句子里的数量）。 */
    private static int quantityAt(List<?> modelItems, int index, int fallback) {
        if (index >= modelItems.size() || !(modelItems.get(index) instanceof Map<?, ?> map)) {
            return fallback;
        }
        Object quantity = map.get("quantity");
        if (quantity instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        return fallback;
    }

    /**
     * 补订单号：先看上下文（消息里抽到的），再退到"用户说的这单指哪一单"。
     *
     * <p>第二段是 2026-11-11 补的：模型选对了 {@code CANCEL_ORDER} / {@code REQUEST_RETURN}，
     * 但用户只说「这单我不要了」没给单号 —— 以前只能回"请提供订单号"。
     * 现在按动作去取**最近一笔符合状态的订单**（规则见 {@link OrderReferenceResolver}），
     * 取不到才要求用户给单号（宁可少猜，不猜错单子）。
     */
    private void fillOrderNo(Map<String, Object> arguments, WorkflowContext context, String tool) {
        Object current = arguments.get("orderNo");
        boolean blank = current == null || String.valueOf(current).isBlank();
        if (!blank) {
            return;
        }
        if (context.orderNo() != null && !context.orderNo().isBlank()) {
            arguments.put("orderNo", context.orderNo());
            return;
        }
        resolveRecentOrderNo(context, tool).ifPresent(orderNo -> {
            arguments.put("orderNo", orderNo);
            log.info("「{}」没写订单号，按最近一笔订单处理：{}", context.userMessage(), orderNo);
        });
    }

    /** 按动作类型取最近一笔合适的订单号（取消要未支付、退款退货要已支付）。 */
    private java.util.Optional<String> resolveRecentOrderNo(WorkflowContext context, String tool) {
        return switch (tool) {
            case ToolNames.CANCEL_ORDER -> orderReferenceResolver.recentCancellable(context.customerId());
            case ToolNames.REQUEST_REFUND, ToolNames.QUERY_REFUND,
                 ToolNames.REQUEST_RETURN, ToolNames.QUERY_RETURN -> orderReferenceResolver.recentPaid(context.customerId());
            default -> orderReferenceResolver.recentAny(context.customerId());
        };
    }

    /** 是否属于"购买 / 加购"这类需要商品的动作（只有这类才做品类歧义检查）。 */
    private static boolean isPurchaseOrCartAction(String message) {
        return QuestionSignals.hasPurchaseIntent(message) || QuestionSignals.isAddToCartRequest(message);
    }

    /** 补预算金额。 */
    private void fillBudget(Map<String, Object> arguments, String message) {
        Object current = arguments.get("budget");
        boolean blank = current == null || String.valueOf(current).isBlank();
        if (blank) {
            Integer budget = QuestionSignals.extractBudgetAmount(message);
            if (budget != null) {
                arguments.put("budget", budget);
            }
        }
    }

    /** 补检索词：为空时用原句（目录类问题用统一口径的查询词）。 */
    private void fillQuery(Map<String, Object> arguments, String message) {
        Object current = arguments.get("query");
        boolean blank = current == null || String.valueOf(current).isBlank();
        if (blank) {
            arguments.put("query", QuestionSignals.isCatalogOrBudgetQuestion(message)
                    ? QuestionSignals.catalogQueryFor(message)
                    : message);
        }
    }

    /**
     * 购物车三态（加购 / 结算 / 查看）的工具调用，用来**抢在"下单"判定之前**。
     *
     * <p>真机踩过（2026-11-11）：用户说「我想要买个切尔西短靴、藏青帆布鞋、深灰针织开衫。先帮我加入购物车」，
     * 句子里既有购买词（"买个"）又能定位到商品，于是 {@code purchaseToolCalls} 先命中，
     * 直接生成了一张待确认订单 —— 而用户要的是**加购**。
     * 结论：只要句子里出现"购物车"这一路意图，动作就按购物车来，不要升级成下单。
     */
    private List<ToolCall> cartToolCalls(String message, WorkflowContext context, String title) {
        boolean cartIntent = QuestionSignals.isAddToCartRequest(message)
                || QuestionSignals.isCartCheckoutRequest(message)
                || QuestionSignals.isViewCartRequest(message);
        return cartIntent ? afterSalesToolCalls(message, context, title) : List.of();
    }

    /**
     * 购物车 / 售后 / 账户的**确定性工具**（2026-11-09）。
     *
     * <p>为什么放在这里、并在 {@code parseOrFallback} 里**覆盖模型输出**：这几路原来只有路由、没有工具，
     * 动作完全看模型心情 —— 实测「加入购物车」被当成下单（还编了个不存在的商品编号）、
     * 「帮我取消订单」工具 0 个直接哑火、「确认收货」只回一句订单状态（用户会以为操作成功）。
     * 现在这些动作由代码决定，模型只负责组织措辞。
     *
     * <p>顺序即优先级：加购 > 结算 > 查看车 > 取消 > 退款 > 账户 > 其余订单生命周期（转人工）。
     */
    private List<ToolCall> afterSalesToolCalls(String message, WorkflowContext context, String title) {
        String orderNo = context.orderNo() == null ? "" : context.orderNo();
        if (QuestionSignals.isAddToCartRequest(message)) {
            List<Map<String, Object>> cartItems = QuestionSignals.productItemsIn(message).stream()
                    .map(item -> Map.<String, Object>of("productId", item.productId(), "quantity", item.quantity()))
                    .toList();
            return List.of(new ToolCall(ToolNames.ADD_TO_CART, Map.of("items", cartItems)));
        }
        if (QuestionSignals.isCartCheckoutRequest(message)) {
            // "把购物车里的都下单"：整车转成一张待确认单（下单成功后由工具清空购物车）
            return List.of(new ToolCall(ToolNames.CREATE_ORDER, Map.of("fromCart", true)));
        }
        if (QuestionSignals.isViewCartRequest(message)) {
            return List.of(new ToolCall(ToolNames.VIEW_CART, Map.of()));
        }
        if (QuestionSignals.isCancelOrderRequest(message)) {
            return List.of(new ToolCall(ToolNames.CANCEL_ORDER, Map.of("orderNo", orderNo)));
        }
        if (QuestionSignals.isReturnStatusQuery(message)) {
            return List.of(new ToolCall(ToolNames.QUERY_RETURN, Map.of("orderNo", orderNo)));
        }
        if (QuestionSignals.isReturnRequest(message)) {
            // 退货 ≠ 退款：货寄回，人工核验后才退款
            return List.of(new ToolCall(ToolNames.REQUEST_RETURN, Map.of("orderNo", orderNo, "reason", message)));
        }
        if (QuestionSignals.isRefundStatusQuery(message)) {
            // 「我的退款到哪了」查库，不讲流程（2026-11-09 补：能申请不能查太伤信任）
            return List.of(new ToolCall(ToolNames.QUERY_REFUND, Map.of("orderNo", orderNo)));
        }
        if (QuestionSignals.isRefundRequest(message)) {
            return List.of(new ToolCall(ToolNames.REQUEST_REFUND, Map.of("orderNo", orderNo, "reason", message)));
        }
        if (QuestionSignals.isAccountQuery(message)) {
            return List.of(new ToolCall(ToolNames.QUERY_ACCOUNT, Map.of()));
        }
        if (QuestionSignals.isOrderLifecycleAction(message)) {
            // 剩下的（确认收货 / 修改地址…）对话里没有对应工具：**明确转人工**，
            // 绝不像过去那样"回个订单状态就完事"（用户会以为操作已经成功）。
            return List.of(
                    new ToolCall(ToolNames.CREATE_TICKET, Map.of(
                            "customerId", context.customerId(),
                            "title", "售后处理：" + title,
                            "description", message,
                            "priority", TicketPriority.HIGH.name())),
                    new ToolCall(ToolNames.SEARCH_KNOWLEDGE, Map.of("query", message, "limit", 3)));
        }
        return List.of();
    }

    /**
     * 明确下单且能定位到商品时生成**一个** {@code CREATE_ORDER}，商品放在 {@code items} 数组里。
     *
     * <p>为什么不是"一件一个调用"：确认单本身支持多明细（V9），一张单一次确认更接近真实下单体验，
     * 也不会出现"确认了两张卡、只点了一张"的漏单。识别口径在 {@code QuestionSignals.purchaseItems}。
     */
    private List<ToolCall> purchaseToolCalls(String message) {
        List<PurchaseItem> items = QuestionSignals.purchaseItems(message);
        if (items.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> arguments = items.stream()
                .map(item -> Map.<String, Object>of("productId", item.productId(), "quantity", item.quantity()))
                .toList();
        return List.of(new ToolCall(ToolNames.CREATE_ORDER, Map.of("items", arguments)));
    }
    private String defaultTitle(String message) {
        if (message.contains("支付")) {
            return "支付异常处理";
        }
        if (message.contains("订单")) {
            return "订单问题咨询";
        }
        return message.length() > 18 ? message.substring(0, 18) : message;
    }
}
