package com.example.aiticketassistant.application.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aiticketassistant.application.agent.AiClientPort;
import com.example.aiticketassistant.application.conversation.ConversationHistory;
import com.example.aiticketassistant.application.conversation.ConversationMemory;
import com.example.aiticketassistant.application.conversation.ConversationTurn;
import com.example.aiticketassistant.application.conversation.SessionSummary;
import com.example.aiticketassistant.application.agent.AiRequest;
import com.example.aiticketassistant.application.agent.AiResponse;
import com.example.aiticketassistant.application.agent.AiStreamEvent;
import com.example.aiticketassistant.application.agent.StreamChunks;
import com.example.aiticketassistant.application.agent.IntentAgent;
import com.example.aiticketassistant.application.agent.IntentResult;
import com.example.aiticketassistant.application.agent.IntentType;
import com.example.aiticketassistant.application.agent.KnowledgeAgent;
import com.example.aiticketassistant.application.agent.OrderAgent;
import com.example.aiticketassistant.application.assembler.AssistantResponseAssembler;
import com.example.aiticketassistant.application.tool.ToolCall;
import com.example.aiticketassistant.application.tool.ToolDispatcher;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolRegistry;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.application.workflow.WorkflowTraceStatus;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchResult;
import com.example.aiticketassistant.domain.ticket.TicketPriority;
import com.example.aiticketassistant.infrastructure.prompt.ClasspathPromptCatalog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

class AssistantWorkflowServiceTest {
    /** 意图 Agent 桩：解析成功但**不给任何工具调用**（路由交给模型后，直答类用例都要它）。 */
    private static IntentAgent noToolIntentAgent() {
        return new IntentAgent(
                request -> AiResponse.fallback("{\"intent\":\"GENERAL_SUPPORT\",\"title\":\"闲聊\",\"priority\":\"MEDIUM\","
                        + "\"confidence\":0.9,\"tool_calls\":[]}"),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ClasspathPromptCatalog(),
                "fallback", "legacy",
                new com.example.aiticketassistant.application.catalog.CategoryCandidateService(
                        com.example.aiticketassistant.support.FakeRepositories.emptyProducts()),
                new com.example.aiticketassistant.application.order.OrderReferenceResolver(
                        com.example.aiticketassistant.support.FakeRepositories.emptyOrders()));
    }

    @Test
    void budgetQuestionUsesAgentWorkflowSoModelGetsCatalogData() {
        // 行为变更（2026-11-03）：「我有1000元，最多可以买几件衣服」以前被判成闲聊走直答，
        // 模型手里没有商品数据只能反问单价；现在必须进工作流，由 IntentAgent 触发知识库检索。
        TestFixture fixture = new TestFixture();
        when(fixture.aiClient.complete(any())).thenReturn(AiResponse.fallback("1000元最多可以买9件T恤。"));

        List<AssistantEvent> events = fixture.service.stream(request("我有1000元，最多可以买几件衣服？")).collectList().block();

        assertThat(events).isNotNull();
        verify(fixture.intentAgent).analyze(any(), anyString());
    }

    @Test
    void chitchatStillUsesDirectQwenOnly() {
        TestFixture fixture = new TestFixture();
        when(fixture.aiClient.complete(any())).thenReturn(AiResponse.fallback("不客气。"));

        List<AssistantEvent> events = fixture.service.stream(request("谢谢，明白了")).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AssistantEvent::type).containsOnly(AssistantEventType.FINAL);
        assertThat(events.get(events.size() - 1).payload()).containsEntry("done", true);
        ArgumentCaptor<AiRequest> request = forClass(AiRequest.class);
        verify(fixture.aiClient).complete(request.capture());
        assertThat(request.getValue().agentName()).isEqualTo("DirectQwen");
        verify(fixture.intentAgent, never()).analyze(any(), anyString());
        verify(fixture.toolDispatcher, never()).dispatchAll(any(), any());
    }

    @Test
    void directAnswerCarriesKnowledgeSourcesWhenRetrieved() {
        // 直答路径以前完全不查知识库（需要资料的问法只能靠模型自己编），现在先说"查到了什么"
        TestFixture fixture = new TestFixture();
        when(fixture.knowledgeAgent.retrieveKnowledge(any())).thenAnswer(invocation -> {
            WorkflowContext context = invocation.getArgument(0);
            context.knowledgeResults().add(new KnowledgeSearchResult(
                    new KnowledgeChunk("CHK-PAY-001-1", "DOC-PAY-001", "退款政策", "PAYMENT",
                            "internal://kb/refund", "签收后 7 天内可退。"),
                    0.0, 0.71, 0.71, "bm25-local"));
            return true;
        });
        when(fixture.knowledgeAgent.sourcesBlock(any())).thenReturn("- 退款政策：签收后 7 天内可退。");
        // 路由交给模型后，这句也会先进工作流；意图桩返回"不需要工具"才会落到直答
        when(fixture.intentAgent.analyze(any(), anyString())).thenReturn(new IntentResult(
                IntentType.GENERAL_SUPPORT, "订单异常", TicketPriority.MEDIUM, 0.9, List.of()));
        when(fixture.aiClient.complete(any())).thenReturn(AiResponse.fallback("签收后 7 天内可以退。"));

        // 注意不能问"怎么申请退款"：那句话里有"申请退款"，会被路由进工作流（那是生命周期动作），
        // 而这里要测的是**直答路径**的前置检索
        List<AssistantEvent> events = fixture.service.stream(request("订单显示 FAILED 是什么意思")).collectList().block();

        ArgumentCaptor<AiRequest> captor = forClass(AiRequest.class);
        verify(fixture.aiClient).complete(captor.capture());
        assertThat(captor.getValue().userPrompt())
                .contains("Retrieved knowledge")
                .contains("- 退款政策：签收后 7 天内可退。");
        // 来源必须在正文之前推给前端（右侧 RAG 面板），顺序与走工作流时一致
        assertThat(events).extracting(AssistantEvent::type)
                .containsSubsequence(AssistantEventType.KNOWLEDGE_SOURCES, AssistantEventType.FINAL);
    }

    @Test
    void directAnswerSendsAnEmptyKnowledgeBlockWhenNothingIsRetrieved() {
        TestFixture fixture = new TestFixture();
        when(fixture.knowledgeAgent.retrieveKnowledge(any())).thenReturn(false);
        when(fixture.aiClient.complete(any())).thenReturn(AiResponse.fallback("不客气。"));

        List<AssistantEvent> events = fixture.service.stream(request("谢谢，明白了")).collectList().block();

        ArgumentCaptor<AiRequest> captor = forClass(AiRequest.class);
        verify(fixture.aiClient).complete(captor.capture());
        // 占位符必须仍然被渲染（否则 PromptTemplate 会抛异常），只是内容为空
        assertThat(captor.getValue().userPrompt()).contains("Retrieved knowledge");
        assertThat(events).extracting(AssistantEvent::type).doesNotContain(AssistantEventType.KNOWLEDGE_SOURCES);
        verify(fixture.knowledgeAgent).retrieveKnowledge(any());
    }

    @Test
    void genericOrderQuestionFallsBackToDirectAnswerAfterIntent() {
        // 2026-11-11 起路由交给模型：这类"通用问题"会先进工作流问一次意图，
        // 模型说"不需要工具"（tool_calls 为空）再落回直答 —— 关键是**不能调工具**。
        TestFixture fixture = new TestFixture();
        when(fixture.intentAgent.analyze(any(), anyString())).thenReturn(new IntentResult(
                IntentType.GENERAL_SUPPORT, "通用售后", TicketPriority.MEDIUM, 0.9, List.of()));
        when(fixture.aiClient.complete(any())).thenReturn(AiResponse.fallback("建议先查看支付流水并联系客服核验。"));

        List<AssistantEvent> events = fixture.service.stream(request("支付成功但订单没生成怎么办？")).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AssistantEvent::type)
                .containsSequence(AssistantEventType.WORKFLOW_STARTED, AssistantEventType.AGENT_STARTED)
                .contains(AssistantEventType.AGENT_COMPLETED, AssistantEventType.FINAL)
                .doesNotContain(AssistantEventType.TOOL_CALL);
        verify(fixture.intentAgent).analyze(any(), anyString());
        verify(fixture.toolDispatcher, never()).dispatchAll(any(), any());
    }

    @Test
    void userOrderQueryUsesAgentWorkflow() {
        TestFixture fixture = new TestFixture();
        when(fixture.intentAgent.analyze(any(), anyString())).thenReturn(new IntentResult(
                IntentType.QUERY_ORDER,
                "订单查询",
                TicketPriority.MEDIUM,
                0.9,
                List.of(new ToolCall(ToolNames.QUERY_ORDER, Map.of("customerId", "CUST-1001")))));
        when(fixture.toolDispatcher.dispatchAll(any(), any())).thenReturn(List.of());
        when(fixture.responseAssembler.assemble(any(), any(), any())).thenReturn("已进入订单查询流程。\n");

        List<AssistantEvent> events = fixture.service.stream(request("帮我查一下我的订单")).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AssistantEvent::type)
                .contains(AssistantEventType.WORKFLOW_STARTED, AssistantEventType.AGENT_STARTED, AssistantEventType.TOOL_CALL, AssistantEventType.TRACE);
        verify(fixture.intentAgent).analyze(any(), anyString());
        verify(fixture.toolDispatcher).dispatchAll(any(), any());
    }

    @Test
    void catalogPriceQuestionUsesAgentWorkflow() {
        TestFixture fixture = new TestFixture();
        when(fixture.intentAgent.analyze(any(), anyString())).thenReturn(new IntentResult(
                IntentType.KNOWLEDGE_QA,
                "商品目录查询",
                TicketPriority.MEDIUM,
                0.9,
                List.of(new ToolCall(ToolNames.SEARCH_KNOWLEDGE, Map.of("query", "价格刚好699的衣服是什么", "limit", 5)))));
        when(fixture.toolDispatcher.dispatchAll(any(), any())).thenReturn(List.of());
        when(fixture.responseAssembler.assemble(any(), any(), any())).thenReturn("价格刚好699元的是羊毛混纺大衣。\n");

        List<AssistantEvent> events = fixture.service.stream(request("价格刚好699的衣服是什么")).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AssistantEvent::type)
                .contains(AssistantEventType.WORKFLOW_STARTED, AssistantEventType.AGENT_STARTED, AssistantEventType.TOOL_CALL, AssistantEventType.TRACE);
        verify(fixture.intentAgent).analyze(any(), anyString());
        verify(fixture.toolDispatcher).dispatchAll(any(), any());
    }

    @Test
    void catalogBudgetQuestionUsesAgentWorkflow() {
        TestFixture fixture = new TestFixture();
        when(fixture.intentAgent.analyze(any(), anyString())).thenReturn(new IntentResult(
                IntentType.KNOWLEDGE_QA,
                "商品目录筛选",
                TicketPriority.MEDIUM,
                0.9,
                List.of(new ToolCall(ToolNames.SEARCH_KNOWLEDGE, Map.of("query", "我的衣服比699便宜的有哪几种，我预算有限", "limit", 5)))));
        when(fixture.toolDispatcher.dispatchAll(any(), any())).thenReturn(List.of());
        when(fixture.responseAssembler.assemble(any(), any(), any())).thenReturn("比699便宜的衣服有多种。\n");

        List<AssistantEvent> events = fixture.service.stream(request("我的衣服比699便宜的有哪几种，我预算有限")).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AssistantEvent::type)
                .contains(AssistantEventType.WORKFLOW_STARTED, AssistantEventType.AGENT_STARTED, AssistantEventType.TOOL_CALL, AssistantEventType.TRACE);
        verify(fixture.intentAgent).analyze(any(), anyString());
        verify(fixture.toolDispatcher).dispatchAll(any(), any());
    }

    @Test
    void purchaseSynonymUsesAgentWorkflow() {
        TestFixture fixture = new TestFixture();
        when(fixture.intentAgent.analyze(any(), anyString())).thenReturn(new IntentResult(
                IntentType.GENERAL_SUPPORT,
                "安全下单确认",
                TicketPriority.MEDIUM,
                0.9,
                List.of(new ToolCall(ToolNames.CREATE_ORDER, Map.of("productId", "CLOTH-HOODIE-004", "quantity", 1)))));
        when(fixture.toolDispatcher.dispatchAll(any(), any())).thenReturn(List.of());
        when(fixture.responseAssembler.assemble(any(), any(), any())).thenReturn("已进入下单确认流程。\n");

        List<AssistantEvent> events = fixture.service.stream(request("来一件黑色卫衣")).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AssistantEvent::type)
                .contains(AssistantEventType.WORKFLOW_STARTED, AssistantEventType.AGENT_STARTED, AssistantEventType.TOOL_CALL, AssistantEventType.TRACE);
        verify(fixture.intentAgent).analyze(any(), anyString());
        verify(fixture.toolDispatcher).dispatchAll(any(), any());
    }

    @Test
    void purchaseActionUsesAgentWorkflow() {
        TestFixture fixture = new TestFixture();
        when(fixture.intentAgent.analyze(any(), anyString())).thenReturn(new IntentResult(
                IntentType.GENERAL_SUPPORT,
                "安全下单确认",
                TicketPriority.MEDIUM,
                0.9,
                List.of(new ToolCall(ToolNames.CREATE_ORDER, Map.of("productId", "CLOTH-TEE-001", "quantity", 1)))));
        when(fixture.toolDispatcher.dispatchAll(any(), any())).thenReturn(List.of());
        when(fixture.responseAssembler.assemble(any(), any(), any())).thenReturn("已进入下单确认流程。\n");

        List<AssistantEvent> events = fixture.service.stream(request("我要买一件黑色卫衣")).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(AssistantEvent::type)
                .contains(AssistantEventType.WORKFLOW_STARTED, AssistantEventType.AGENT_STARTED, AssistantEventType.TOOL_CALL, AssistantEventType.TRACE);
        verify(fixture.intentAgent).analyze(any(), anyString());
        verify(fixture.toolDispatcher).dispatchAll(any(), any());
    }

    @Test
    void traceStatusMapsNormalCompletionToCompleted() {
        assertThat(AssistantWorkflowService.traceStatusFor(SignalType.ON_COMPLETE, false))
                .isEqualTo(WorkflowTraceStatus.COMPLETED);
    }

    @Test
    void traceStatusMapsFlaggedFailureToFailed() {
        // 工作流异常被 onErrorResume 兜成 error 事件后，信号仍是 ON_COMPLETE，只能靠标记识别
        assertThat(AssistantWorkflowService.traceStatusFor(SignalType.ON_COMPLETE, true))
                .isEqualTo(WorkflowTraceStatus.FAILED);
    }

    @Test
    void traceStatusMapsErrorSignalToFailed() {
        assertThat(AssistantWorkflowService.traceStatusFor(SignalType.ON_ERROR, false))
                .isEqualTo(WorkflowTraceStatus.FAILED);
    }

    @Test
    void traceStatusMapsClientCancelToCancelled() {
        assertThat(AssistantWorkflowService.traceStatusFor(SignalType.CANCEL, false))
                .isEqualTo(WorkflowTraceStatus.CANCELLED);
    }

    @Test
    void recordsExactlyOneTraceWhenStreamCompletes() throws InterruptedException {
        List<WorkflowTraceStatus> recorded = new CopyOnWriteArrayList<>();
        AssistantWorkflowService traced = new AssistantWorkflowService(
                noToolIntentAgent(),
                mock(OrderAgent.class),
                mock(KnowledgeAgent.class),
                mock(ToolDispatcher.class),
                mockToolRegistry(),
                mock(AssistantResponseAssembler.class),
                request -> AiResponse.fallback("1000元最多可以买9件T恤。"),
                new ClasspathPromptCatalog(),
                (context, status, completedAt) -> recorded.add(status),
                emptyMemory(),
                ConversationHistory.Budget.defaults());

        traced.stream(request("谢谢，明白了"))
                .doOnNext(event -> {})
                .collectList()
                .block();

        // 落库走 boundedElastic 异步执行，这里等它跑完（最多 3s）
        long deadline = System.currentTimeMillis() + 3000;
        while (recorded.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(recorded).containsExactly(WorkflowTraceStatus.COMPLETED);
    }

    private static ToolRegistry mockToolRegistry() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.metadata()).thenReturn(List.of());
        return registry;
    }

    @Test
    void injectsConversationHistoryIntoDirectAnswerPrompt() {
        // 有历史时，历史文本必须出现在发给 AI 的 user prompt 里（多轮记忆的核心契约）
        AiClientPort ai = mock(AiClientPort.class);
        when(ai.complete(any())).thenReturn(AiResponse.fallback("给你推荐这几件。"));
        stubStreamFromComplete(ai);
        ConversationTurn previous = new ConversationTurn("我有1000元，最多可以买几件衣服", "最多可以买10件T恤。");
        AssistantWorkflowService withHistory = new AssistantWorkflowService(
                noToolIntentAgent(),
                mock(OrderAgent.class),
                mock(KnowledgeAgent.class),
                mock(ToolDispatcher.class),
                mockToolRegistry(),
                mock(AssistantResponseAssembler.class),
                ai,
                new ClasspathPromptCatalog(),
                (context, status, completedAt) -> {},
                memoryOf(List.of(previous)),
                ConversationHistory.Budget.defaults());

        withHistory.stream(request("那件红色的呢")).collectList().block();

        ArgumentCaptor<AiRequest> captor = forClass(AiRequest.class);
        verify(ai).complete(captor.capture());
        assertThat(captor.getValue().userPrompt())
                .contains("用户: 我有1000元，最多可以买几件衣服")
                .contains("助手: 最多可以买10件T恤。")
                .contains("User message: 那件红色的呢");
    }

    @Test
    void longHistoryIsTrimmedBeforeItReachesThePrompt() {
        // 6 轮 × 每轮回答约 2000 字 = 一万多字。以前整段塞进 4 个提示词（又贵又容易把模型带偏），
        // 现在必须在进入 prompt 之前就被裁到预算内，且最新一轮不能丢。
        List<ConversationTurn> verbose = new java.util.ArrayList<>();
        for (int round = 1; round <= 6; round++) {
            verbose.add(new ConversationTurn("第" + round + "轮问题", "第" + round + "轮回答 " + "很长的明细表内容。".repeat(250)));
        }
        AiClientPort ai = mock(AiClientPort.class);
        when(ai.complete(any())).thenReturn(AiResponse.fallback("好的。"));
        stubStreamFromComplete(ai);
        AssistantWorkflowService service = new AssistantWorkflowService(
                noToolIntentAgent(),
                mock(OrderAgent.class),
                mock(KnowledgeAgent.class),
                mock(ToolDispatcher.class),
                mockToolRegistry(),
                mock(AssistantResponseAssembler.class),
                ai,
                new ClasspathPromptCatalog(),
                (context, status, completedAt) -> {},
                memoryOf(verbose),
                new ConversationHistory.Budget(6, 800, 120, 200));

        service.stream(request("那不加重复呢")).collectList().block();

        ArgumentCaptor<AiRequest> captor = forClass(AiRequest.class);
        verify(ai).complete(captor.capture());
        String prompt = captor.getValue().userPrompt();
        String historyBlock = prompt.substring(prompt.indexOf("用户: "), prompt.indexOf("User message:")).trim();

        assertThat(historyBlock).contains("用户: 第6轮问题");      // 最新一轮必须保留
        assertThat(historyBlock).doesNotContain("第1轮问题");       // 最早的被丢掉
        assertThat(historyBlock).contains("…");                     // 长回答被截断
        assertThat(historyBlock.length()).isLessThanOrEqualTo(820); // 预算 800 字（含每轮前缀开销的少量余量）
    }

    @Test
    void directAnswerStreamsEachTokenAsItsOwnFinalEvent() {
        // 真流式适配器：故意把一句话拆成 3 个 token —— 按句切片是做不出这个粒度的
        AiClientPort streaming = new AiClientPort() {
            @Override
            public AiResponse complete(AiRequest request) {
                throw new AssertionError("直答必须走 stream()，不能再整段 complete()");
            }

            @Override
            public Flux<AiStreamEvent> stream(AiRequest request) {
                return Flux.just(AiStreamEvent.delta("这是"), AiStreamEvent.delta("流式"), AiStreamEvent.delta("回答。"));
            }
        };
        AssistantWorkflowService service = new AssistantWorkflowService(
                noToolIntentAgent(),
                mock(OrderAgent.class),
                mock(KnowledgeAgent.class),
                mock(ToolDispatcher.class),
                mockToolRegistry(),
                mock(AssistantResponseAssembler.class),
                streaming,
                new ClasspathPromptCatalog(),
                (context, status, completedAt) -> {},
                emptyMemory(),
                ConversationHistory.Budget.defaults());

        List<AssistantEvent> events = service.stream(request("你好")).collectList().block();

        List<AssistantEvent> finals = events.stream().filter(event -> event.type() == AssistantEventType.FINAL).toList();
        assertThat(finals).as("3 个增量帧 + 1 个收尾帧").hasSize(4);
        assertThat(finals.subList(0, 3)).extracting(event -> event.payload().get("delta"))
                .containsExactly("这是", "流式", "回答。");
        assertThat(finals.subList(0, 3)).allSatisfy(event -> assertThat(event.payload()).containsEntry("done", false));
        assertThat(finals.get(3).payload())
                .containsEntry("done", true)
                .containsEntry("fullText", "这是流式回答。\n");
    }

    @Test
    void knowledgeTokensStreamFirstThenDeterministicSectionsFollow() {
        TestFixture fixture = new TestFixture();
        when(fixture.intentAgent.analyze(any(), anyString())).thenReturn(new IntentResult(
                IntentType.ORDER_AND_KNOWLEDGE,
                "订单+商品知识",
                TicketPriority.MEDIUM,
                0.9,
                List.of(new ToolCall(ToolNames.QUERY_ORDER, Map.of("customerId", "CUST-1001")),
                        new ToolCall(ToolNames.SEARCH_KNOWLEDGE, Map.of("query", "价格刚好699的衣服是什么", "limit", 5)))));
        when(fixture.toolDispatcher.dispatchAll(any(), any())).thenAnswer(invocation -> {
            // 真实 dispatcher 会把结果写回上下文，mock 必须手动补，否则工作流看不到"工具成功过"
            WorkflowContext context = invocation.getArgument(1);
            List<ToolResult> results = List.of(
                    ToolResult.success(ToolNames.QUERY_ORDER, Map.of("orderNo", "ORD-1001")),
                    ToolResult.success(ToolNames.SEARCH_KNOWLEDGE, Map.of("sources", List.of())));
            context.toolResults().addAll(results);
            context.knowledgeResults().add(new KnowledgeSearchResult(
                    new KnowledgeChunk("CHK-CATALOG-CLOTHING-COAT-009", "DOC-CATALOG-CLOTHING",
                            "服装商品目录：羊毛混纺大衣", "CATALOG", "internal://catalog/clothing#COAT-009",
                            "商品编号 CLOTH-COAT-009，商品名称 羊毛混纺大衣，服装商品，价格 ¥699.00。"),
                    0.0, 0.9, 0.9, "bm25-local"));
            return results;
        });
        when(fixture.knowledgeAgent.streamAnswer(any())).thenReturn(Flux.just("结论：", "羊毛混纺大衣 ¥699。"));
        when(fixture.orderAgent.analyze(any())).thenReturn("订单正常。");
        when(fixture.responseAssembler.sectionsAfterKnowledge(any(), any())).thenReturn("**订单情况**\n订单正常。");

        List<AssistantEvent> events = fixture.service.stream(request("价格刚好699的衣服是什么，顺便看看订单 ORD-1001"))
                .collectList().block();

        // 顺序：RAG 来源 → token 增量 → 确定性小节 → done
        assertThat(events).extracting(AssistantEvent::type)
                .containsSubsequence(AssistantEventType.KNOWLEDGE_SOURCES, AssistantEventType.FINAL,
                        AssistantEventType.AGENT_COMPLETED, AssistantEventType.FINAL, AssistantEventType.TRACE);
        String fullText = events.stream()
                .filter(event -> event.type() == AssistantEventType.FINAL)
                .filter(event -> event.payload().get("delta") != null)   // 收尾帧只带 fullText，没有 delta
                .map(event -> String.valueOf(event.payload().get("delta")))
                .collect(java.util.stream.Collectors.joining());
        // 增量拼起来 = 模型输出 + 空行 + 确定性小节（与 assemble() 的排版一致）
        assertThat(fullText).isEqualTo("结论：羊毛混纺大衣 ¥699。\n\n**订单情况**\n订单正常。");
        assertThat(events.get(events.size() - 2).payload()).containsEntry("fullText", fullText + "\n").containsEntry("done", true);
    }

    private static ConversationMemory emptyMemory() {
        return new ConversationMemory() {
            @Override
            public List<ConversationTurn> recentTurns(String sessionId, int maxTurns) {
                return List.of();
            }

            @Override
            public void recordTurn(String sessionId, String customerId, String userMessage, String assistantAnswer) {
            }

            @Override
            public boolean deleteSession(String sessionId, String customerId) {
                return false;
            }

            public void requireOwnership(String sessionId, String customerId) {
                // 测试桩：归属校验由 AuthSmokeTest / 控制器层覆盖
            }

            @Override
            public List<SessionSummary> recentSessions(String customerId, int limit) {
                return List.of();
            }
        };
    }

    private static ConversationMemory memoryOf(List<ConversationTurn> turns) {
        return new ConversationMemory() {
            @Override
            public List<ConversationTurn> recentTurns(String sessionId, int maxTurns) {
                return turns;
            }

            @Override
            public void recordTurn(String sessionId, String customerId, String userMessage, String assistantAnswer) {
            }

            @Override
            public boolean deleteSession(String sessionId, String customerId) {
                return false;
            }

            public void requireOwnership(String sessionId, String customerId) {
                // 测试桩：归属校验由 AuthSmokeTest / 控制器层覆盖
            }

            @Override
            public List<SessionSummary> recentSessions(String customerId, int limit) {
                return List.of();
            }
        };
    }
    private static AssistantRequest request(String message) {
        return new AssistantRequest("s", "CUST-1001", null, message);
    }

    /**
     * Mockito 的 mock **不会走接口默认方法**：不 stub 的话 {@code stream()} 直接返回 null，
     * 工作流会在组装 Flux 时 NPE。这里把 {@code stream()} 接到 {@code complete()} 上，
     * 等价于"伪流式适配器"（整段切句）—— 真流式的逐 token 行为见专门的用例。
     */
    private static void stubStreamFromComplete(AiClientPort ai) {
        when(ai.stream(any())).thenAnswer(invocation -> {
            AiRequest request = invocation.getArgument(0);
            return Flux.fromIterable(StreamChunks.sentences(ai.complete(request).text()))
                    .map(AiStreamEvent::delta);
        });
    }

    private static class TestFixture {
        private final IntentAgent intentAgent = mock(IntentAgent.class);
        private final OrderAgent orderAgent = mock(OrderAgent.class);
        private final KnowledgeAgent knowledgeAgent = mock(KnowledgeAgent.class);
        private final ToolDispatcher toolDispatcher = mock(ToolDispatcher.class);
        private final ToolRegistry toolRegistry = mock(ToolRegistry.class);
        private final AssistantResponseAssembler responseAssembler = mock(AssistantResponseAssembler.class);
        private final AiClientPort aiClient = mock(AiClientPort.class);
        private final AssistantWorkflowService service;

        private TestFixture() {
            when(toolRegistry.metadata()).thenReturn(List.of());
            stubStreamFromComplete(aiClient);
            service = new AssistantWorkflowService(
                intentAgent,
                orderAgent,
                knowledgeAgent,
                toolDispatcher,
                toolRegistry,
                    responseAssembler,
                    aiClient,
                    new ClasspathPromptCatalog(),
                    // trace 落库在单测里不做真实写入：用空实现，映射逻辑由 traceStatusFor 单测覆盖
                    (context, status, completedAt) -> {},
                    emptyMemory(),
                    ConversationHistory.Budget.defaults());
        }
    }

    @Test
    void routesBudgetAndQuantityQuestionsIntoAgentWorkflow() {
        // 这些问法以前被判成闲聊走直答，模型拿不到商品数据只能瞎编
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("1000元预算可以怎么搭配")).isTrue();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("我有1000元，最多可以买几件衣服")).isTrue();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("1000元预算，推荐几件衣服怎么搭配")).isTrue();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("价格刚好699的衣服是什么")).isTrue();
    }

    @Test
    void keepsPlainChitchatOnTheDirectPath() {
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("你好")).isFalse();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("谢谢，明白了")).isFalse();
    }

    @Test
    void businessActionsStillRouteIntoAgentWorkflow() {
        // 这些分支的关键词原本只写在本类里，搬到 QuestionSignals 后必须逐条仍然生效
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("把这件卫衣加入购物车")).isTrue();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("我想改一下收货地址")).isTrue();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("帮我取消订单")).isTrue();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("怎么申请退款")).isTrue();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("帮我创建支付失败工单")).isTrue();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("我要投诉")).isTrue();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("查一下 ORD-1001 的状态")).isTrue();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("我有几个订单")).isTrue();
    }

    @Test
    void routerNowAlsoRecognisesQuantityWrittenAfterTheVerb() {
        // 「下单 2 件」以前只有 IntentAgent 认，路由不认 → 被判成闲聊走直答。统一口径后两边一致。
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("下单 2 件黑色卫衣")).isTrue();
    }

    @Test
    void genericKnowledgeQuestionsStayOnTheDirectPath() {
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("支付成功但订单没生成怎么办")).isFalse();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("订单状态一般有哪些")).isFalse();
        assertThat(AssistantWorkflowService.shouldUseAgentWorkflow("推荐一个退款流程")).isFalse();
    }
}
