package com.example.aiticketassistant.application.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.application.catalog.CategoryCandidateService;
import com.example.aiticketassistant.application.order.OrderReferenceResolver;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolCall;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.infrastructure.prompt.ClasspathPromptCatalog;
import com.example.aiticketassistant.support.FakeRepositories;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntentAgentTest {
    /** 使用真实提示词文件：变量名与模板不匹配会在这里直接失败，而不是悄悄发给模型。 */
    private static final ClasspathPromptCatalog PROMPTS = new ClasspathPromptCatalog();
    /** 空目录（不触发品类候选）与空订单（不自动补订单号）：多数用例要的行为。 */
    private static final CategoryCandidateService CANDIDATES =
            new CategoryCandidateService(FakeRepositories.emptyProducts());
    private static final OrderReferenceResolver ORDERS =
            new OrderReferenceResolver(FakeRepositories.emptyOrders());

    private static IntentAgent newIntentAgent(AiClientPort aiClient) {
        return new IntentAgent(aiClient, new ObjectMapper(), PROMPTS, "fallback", "legacy", CANDIDATES, ORDERS);
    }

    @Test
    void decisionModeFollowsProviderUnlessOverridden() {
        // auto（默认）：真模型 → 模型优先；规则兜底引擎 → 关键词层
        assertThat(agentFor("deepseek", "auto").modelFirst()).isTrue();
        assertThat(agentFor("fallback", "auto").modelFirst()).isFalse();
        // 显式覆盖：用于 A/B 与回滚
        assertThat(agentFor("deepseek", "legacy").modelFirst()).isFalse();
        assertThat(agentFor("fallback", "model-first").modelFirst()).isTrue();
        // 拼写容错（model_first 也认）
        assertThat(agentFor("fallback", "model_first").modelFirst()).isTrue();
    }

    @Test
    void fillsProductIdsWhenModelLeavesThemEmpty() {
        // 真机回归（2026-11-11）：模型正确选了 ADD_TO_CART，但每条 item 的 productId 都是空串
        // （提示词要求"不知道就留空"），旧版把"items 非空"当成"已给明细"直接跳过补参，
        // 于是护栏报"商品编号不能为空"。
        AiClientPort fakeAi = request -> AiResponse.fallback("""
                {"intent":"GENERAL_SUPPORT","title":"加购","priority":"MEDIUM","confidence":0.9,
                 "tool_calls":[{"tool":"ADD_TO_CART","arguments":{"items":[
                   {"productId":"","quantity":1},{"productId":"","quantity":1},{"productId":"","quantity":1}]}}]}
                """);
        IntentAgent agent = new IntentAgent(fakeAi, new ObjectMapper(), PROMPTS, "deepseek", "model-first", CANDIDATES, ORDERS);

        IntentResult result = agent.analyze(new WorkflowContext("s", "CUST-1001", null,
                "我想要买军绿工装夹克、轻薄防风夹克、复古牛仔夹克，先帮我加入到购物车先"), "");

        assertThat(result.toolCalls()).hasSize(1);
        ToolCall call = result.toolCalls().get(0);
        assertThat(call.tool()).isEqualTo(ToolNames.ADD_TO_CART);
        List<?> items = (List<?>) call.arguments().get("items");
        assertThat(items).hasSize(3);
        List<String> ids = items.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("productId")))
                .toList();
        assertThat(ids).as("按句子里点名的顺序补上商品编号")
                .containsExactly("CLOTH-JACKET-021", "CLOTH-JACKET-006", "CLOTH-JACKET-038");
    }

    @Test
    void keepsModelProvidedProductIds() {
        // 模型给了具体编号就不要覆盖（可能来自上文指代）
        AiClientPort fakeAi = request -> AiResponse.fallback("""
                {"intent":"GENERAL_SUPPORT","title":"加购","priority":"MEDIUM","confidence":0.9,
                 "tool_calls":[{"tool":"ADD_TO_CART","arguments":{"items":[{"productId":"CLOTH-TEE-001","quantity":2}]}}]}
                """);
        IntentAgent agent = new IntentAgent(fakeAi, new ObjectMapper(), PROMPTS, "deepseek", "model-first", CANDIDATES, ORDERS);

        IntentResult result = agent.analyze(new WorkflowContext("s", "CUST-1001", null,
                "把米色帆布双肩包加入购物车"), "");

        List<?> items = (List<?>) result.toolCalls().get(0).arguments().get("items");
        List<String> ids = items.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("productId")))
                .toList();
        assertThat(ids).containsExactly("CLOTH-TEE-001");
    }

    @Test
    void fillsOrderItemsWhenModelChoosesCreateOrderWithoutItems() {
        // 模型选了 CREATE_ORDER 但没给明细（或明细为空）→ 用句子里点名的商品补
        AiClientPort fakeAi = request -> AiResponse.fallback("""
                {"intent":"GENERAL_SUPPORT","title":"下单","priority":"MEDIUM","confidence":0.9,
                 "tool_calls":[{"tool":"CREATE_ORDER","arguments":{}}]}
                """);
        IntentAgent agent = new IntentAgent(fakeAi, new ObjectMapper(), PROMPTS, "deepseek", "model-first", CANDIDATES, ORDERS);

        IntentResult result = agent.analyze(new WorkflowContext("s", "CUST-1001", null,
                "帮我下单米色帆布双肩包和藏青帆布鞋"), "");

        List<?> items = (List<?>) result.toolCalls().get(0).arguments().get("items");
        assertThat(items).hasSize(2);
    }

    @Test
    void keepsEveryToolTheModelAskedFor() {
        // 模型一次给多个工具：不移除、不合并（执行顺序由 ToolDispatcher 负责）
        AiClientPort fakeAi = request -> AiResponse.fallback("""
                {"intent":"ORDER_AND_KNOWLEDGE","title":"查询并解释","priority":"MEDIUM","confidence":0.9,
                 "tool_calls":[{"tool":"QUERY_ORDER","arguments":{"orderNo":"ORD-1001"}},
                               {"tool":"SEARCH_KNOWLEDGE","arguments":{"query":"订单状态 FAILED"}}]}
                """);
        IntentAgent agent = new IntentAgent(fakeAi, new ObjectMapper(), PROMPTS, "deepseek", "model-first", CANDIDATES, ORDERS);

        IntentResult result = agent.analyze(new WorkflowContext("s", "CUST-1001", "ORD-1001",
                "ORD-1001 为什么失败"), "");

        assertThat(result.toolCalls()).extracting(ToolCall::tool)
                .containsExactly(ToolNames.QUERY_ORDER, ToolNames.SEARCH_KNOWLEDGE);
    }

    private static IntentAgent agentFor(String provider, String mode) {
        return new IntentAgent(request -> AiResponse.fallback("{}"), new ObjectMapper(), PROMPTS, provider, mode, CANDIDATES, ORDERS);
    }

    @Test
    void ignoresNonCallableToolFromModelOutput() {
        AiClientPort fakeAi = request -> AiResponse.fallback("""
                {"intent":"GENERAL_SUPPORT","title":"预算买衣服","priority":"MEDIUM","confidence":0.95,"tool_calls":[{"tool":"SEARCH_PRODUCTS","arguments":{"maxPrice":1000,"category":"clothing"}}]}
                """);
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我只有1000元，最多可以买几件衣服"),
                "");

        assertThat(result.toolCalls()).noneMatch(call -> "SEARCH_PRODUCTS".equals(call.tool()));
        // 模型给了一个不可调用的工具：既不能执行它，也不能因此什么都不检索；
        // 同时"预算内最多买几件"会强制追加 PLAN_BUDGET，件数由代码算
        assertThat(result.toolCalls()).extracting(ToolCall::tool)
                .containsExactly(ToolNames.SEARCH_KNOWLEDGE, ToolNames.PLAN_BUDGET);
    }

    @Test
    void explicitPurchaseStillCreatesOrderToolCall() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我要买一件黑色卫衣"),
                "");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).tool()).isEqualTo(ToolNames.CREATE_ORDER);
        assertThat(createOrderItems(result))
                .containsExactly(java.util.Map.of("productId", "CLOTH-HOODIE-004", "quantity", 1));
    }

    @Test
    void catalogPriceQuestionCreatesKnowledgeSearchToolCall() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "价格刚好699的衣服是什么"),
                "");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).tool()).isEqualTo(ToolNames.SEARCH_KNOWLEDGE);
        assertThat(result.toolCalls().get(0).arguments()).containsEntry("query", "价格刚好699的衣服是什么");
    }

    @Test
    void catalogBudgetQuestionCreatesKnowledgeSearchToolCall() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我的衣服比699便宜的有哪几种，我预算有限"),
                "");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).tool()).isEqualTo(ToolNames.SEARCH_KNOWLEDGE);
        assertThat(result.toolCalls().get(0).arguments()).containsEntry("query", "我的衣服比699便宜的有哪几种，我预算有限");
    }

    @Test
    void cheapestCatalogQuestionCreatesKnowledgeSearchToolCall() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "价格最便宜的衣服是哪一件"),
                "");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).tool()).isEqualTo(ToolNames.SEARCH_KNOWLEDGE);
    }

    @Test
    void purchaseIntentOverridesModelKnowledgeSearchToolCall() {
        AiClientPort fakeAi = request -> AiResponse.fallback("""
                {"intent":"KNOWLEDGE_QA","title":"商品知识查询","priority":"MEDIUM","confidence":0.95,"tool_calls":[{"tool":"SEARCH_KNOWLEDGE","arguments":{"query":"我要买黑色连帽卫衣","limit":5}}]}
                """);
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我要买黑色连帽卫衣"),
                "");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).tool()).isEqualTo(ToolNames.CREATE_ORDER);
        assertThat(createOrderItems(result))
                .containsExactly(java.util.Map.of("productId", "CLOTH-HOODIE-004", "quantity", 1));
    }

    @Test
    void purchaseSynonymStillCreatesOrderToolCall() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "来一件黑色卫衣"),
                "");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).tool()).isEqualTo(ToolNames.CREATE_ORDER);
        assertThat(createOrderItems(result))
                .containsExactly(java.util.Map.of("productId", "CLOTH-HOODIE-004", "quantity", 1));
    }

    @Test
    void multiProductPurchaseCreatesOneOrderCallWithAllItems() {
        // 2026-11-05 真实故障回归：一句话两件商品，以前只处理了一件（静默丢单）。
        // 现在一张确认单带 items 数组，两件都在。
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我想要买米色帆布双肩包和藏青帆布鞋，帮我下单"),
                "");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).tool()).isEqualTo(ToolNames.CREATE_ORDER);
        assertThat(createOrderItems(result)).containsExactly(
                java.util.Map.of("productId", "CLOTH-BAG-029", "quantity", 1),
                java.util.Map.of("productId", "CLOTH-CANVAS-028", "quantity", 1));
    }

    /** 取出 CREATE_ORDER 的 items 数组（参数形态见 {@code CreateOrderTool.metadata()}）。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> createOrderItems(IntentResult result) {
        return (List<Map<String, Object>>) result.toolCalls().get(0).arguments().get("items");
    }

    @Test
    void ticketRequestCreatesTicketToolCall() {
        // 工单意图的判定口径在 QuestionSignals（只要提到"工单"就算），这里锁住启发式仍会补 CREATE_TICKET
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "帮我创建支付失败工单"),
                "");

        assertThat(result.intent()).isEqualTo(IntentType.CREATE_TICKET);
        assertThat(result.toolCalls()).extracting(ToolCall::tool).containsExactly(ToolNames.CREATE_TICKET);
    }

    @Test
    void catalogQueryIsExpandedThroughTheSharedSignals() {
        // 查询词必须由 QuestionSignals.catalogQueryFor 生成（无品类词时补领域词），
        // 不能退回"直接拿原句检索"——那会一条 chunk 都检不到。
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "推荐几件便宜的衣服"),
                "");

        assertThat(result.toolCalls()).extracting(ToolCall::tool).containsExactly(ToolNames.SEARCH_KNOWLEDGE);
        assertThat(result.toolCalls().get(0).arguments()).containsEntry("query", "推荐几件便宜的衣服");
    }

    @Test
    void orderListQuestionStillCreatesQueryOrderToolCall() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我有几个订单"),
                "");

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).tool()).isEqualTo(ToolNames.QUERY_ORDER);
        assertThat(result.toolCalls().get(0).arguments()).containsEntry("customerId", "CUST-1001");
    }

    @Test
    void budgetMatchingQuestionIsGuardedIntoKnowledgeSearch() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "1000元预算可以怎么搭配"),
                "");

        assertThat(result.toolCalls()).extracting(ToolCall::tool).containsExactly(ToolNames.SEARCH_KNOWLEDGE);
        // 没有品类词 → 查询词会补上领域词，否则 BM25 一条都检不到
        assertThat(result.toolCalls().get(0).arguments()).containsEntry("query", "1000元预算可以怎么搭配 服装 商品 价格");
    }

    @Test
    void howManyCanIBuyQuestionIsGuardedIntoKnowledgeSearch() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我有1000元，最多可以买几件衣服"),
                "");

        assertThat(result.toolCalls()).extracting(ToolCall::tool)
                .containsExactly(ToolNames.SEARCH_KNOWLEDGE, ToolNames.PLAN_BUDGET);
    }

    @Test
    void guardDoesNotDuplicateSearchWhenModelAlreadyRequestedIt() {
        AiClientPort fakeAi = request -> AiResponse.fallback("""
                {"intent":"KNOWLEDGE_QA","title":"商品咨询","priority":"MEDIUM","confidence":0.9,"tool_calls":[{"tool":"SEARCH_KNOWLEDGE","arguments":{"query":"毛衣多少钱","limit":5}}]}
                """);
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "毛衣多少钱"),
                "");

        assertThat(result.toolCalls()).extracting(ToolCall::tool).containsExactly(ToolNames.SEARCH_KNOWLEDGE);
    }

    @Test
    void guardLeavesPurchaseFlowUntouched() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我要买一件黑色卫衣"),
                "");

        assertThat(result.toolCalls()).extracting(ToolCall::tool).containsExactly(ToolNames.CREATE_ORDER);
    }

    @Test
    void guardLeavesOrderQueryUntouched() {
        AiClientPort fakeAi = request -> AiResponse.fallback("""
                {"intent":"QUERY_ORDER","title":"订单查询","priority":"MEDIUM","confidence":0.8,"tool_calls":[{"tool":"QUERY_ORDER","arguments":{"customerId":"CUST-1001"}}]}
                """);
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "帮我查一下我的订单"),
                "");

        assertThat(result.toolCalls()).extracting(ToolCall::tool).containsExactly(ToolNames.QUERY_ORDER);
    }

    @Test
    void unknownIntentValueKeepsModelTitleAndTools() {
        // 实测 DeepSeek 会把枚举外的值当意图返回（例如工具名 CREATE_ORDER / PRODUCT_LOOKUP）
        AiClientPort fakeAi = request -> AiResponse.fallback("""
                {"intent":"PRODUCT_LOOKUP","title":"查商品价格","priority":"LOW","confidence":0.8,"tool_calls":[]}
                """);
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "价格刚好699的衣服是什么"),
                "");

        // 意图按启发式补判为 KNOWLEDGE_QA，但模型给的 title/confidence 不再被整体丢弃
        assertThat(result.title()).isEqualTo("查商品价格");
        assertThat(result.intent().name()).isEqualTo("KNOWLEDGE_QA");
        assertThat(result.toolCalls()).extracting(ToolCall::tool).containsExactly(ToolNames.SEARCH_KNOWLEDGE);
    }
    @Test
    void budgetCountingQuestionForcesDeterministicPlan() {
        // 实测模型会自己加总并给出错误合计（934/954/964，真值 1144），所以这类问题必须走 PLAN_BUDGET
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我有1000元，最多可以买几件衣服，衣服不能重复"),
                "");

        ToolCall plan = result.toolCalls().stream()
                .filter(call -> ToolNames.PLAN_BUDGET.equals(call.tool()))
                .findFirst()
                .orElseThrow();
        assertThat(plan.arguments()).containsEntry("budget", 1000).containsEntry("allowRepeat", false);
    }

    @Test
    void budgetQuestionWithoutRepeatConstraintAllowsRepeat() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我有1000元，最多可以买几件衣服"),
                "");

        ToolCall plan = result.toolCalls().stream()
                .filter(call -> ToolNames.PLAN_BUDGET.equals(call.tool()))
                .findFirst()
                .orElseThrow();
        assertThat(plan.arguments()).containsEntry("allowRepeat", true);
    }

    @Test
    void doesNotPlanBudgetWhenNoAmountFound() {
        AiClientPort fakeAi = request -> AiResponse.fallback("{} ");
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "预算有限，最多可以买几件衣服"),
                "");

        assertThat(result.toolCalls()).noneMatch(call -> ToolNames.PLAN_BUDGET.equals(call.tool()));
    }
    @Test
    void budgetGuardOverridesWrongArgumentsFromModel() {
        // 实测模型会自己调 PLAN_BUDGET 并把 allowRepeat 传成 false，导致"最多能买几件"算成 5 件
        AiClientPort fakeAi = request -> AiResponse.fallback("""
                {"intent":"KNOWLEDGE_QA","title":"预算测算","priority":"MEDIUM","confidence":0.9,"tool_calls":[{"tool":"PLAN_BUDGET","arguments":{"budget":1000,"allowRepeat":false}}]}
                """);
        IntentAgent agent = newIntentAgent(fakeAi);

        IntentResult result = agent.analyze(
                new WorkflowContext("s", "CUST-1001", null, "我有1000元，最多可以买几件衣服"),
                "");

        List<ToolCall> plans = result.toolCalls().stream()
                .filter(call -> ToolNames.PLAN_BUDGET.equals(call.tool()))
                .toList();
        // 只能有一条，且参数以代码为准
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).arguments()).containsEntry("allowRepeat", true).containsEntry("budget", 1000);
    }
}