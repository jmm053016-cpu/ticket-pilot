package com.example.aiticketassistant.application.assistant;

import com.example.aiticketassistant.application.agent.AiClientPort;
import com.example.aiticketassistant.application.agent.AiRequest;
import com.example.aiticketassistant.application.agent.AiResponse;
import com.example.aiticketassistant.application.agent.AiStreamEvent;
import com.example.aiticketassistant.application.agent.IntentAgent;
import com.example.aiticketassistant.application.agent.IntentResult;
import com.example.aiticketassistant.application.agent.KnowledgeAgent;
import com.example.aiticketassistant.application.agent.OrderAgent;
import com.example.aiticketassistant.application.agent.StreamChunks;
import com.example.aiticketassistant.application.assembler.AssistantResponseAssembler;
import com.example.aiticketassistant.application.conversation.ConversationHistory;
import com.example.aiticketassistant.application.conversation.ConversationMemory;
import com.example.aiticketassistant.application.conversation.ConversationTurn;
import com.example.aiticketassistant.application.prompt.PromptCatalog;
import com.example.aiticketassistant.application.prompt.PromptIds;
import com.example.aiticketassistant.application.prompt.PromptTemplate;
import com.example.aiticketassistant.application.routing.QuestionSignals;
import com.example.aiticketassistant.application.tool.ToolDispatcher;
import com.example.aiticketassistant.application.tool.ToolMetadata;
import com.example.aiticketassistant.application.tool.ToolNames;
import com.example.aiticketassistant.application.tool.ToolRegistry;
import com.example.aiticketassistant.application.tool.ToolResult;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.application.workflow.WorkflowStep;
import com.example.aiticketassistant.application.workflow.WorkflowTraceRecorder;
import com.example.aiticketassistant.application.workflow.WorkflowTraceStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AssistantWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(AssistantWorkflowService.class);
    /** 最终回答在上下文里的键名，供落库时取用。 */
    private static final String FINAL_ANSWER_KEY = "finalAnswer";

    private final IntentAgent intentAgent;
    private final OrderAgent orderAgent;
    private final KnowledgeAgent knowledgeAgent;
    private final ToolDispatcher toolDispatcher;
    private final ToolRegistry toolRegistry;
    private final AssistantResponseAssembler responseAssembler;
    private final AiClientPort aiClient;
    private final PromptCatalog promptCatalog;
    private final WorkflowTraceRecorder workflowTraceRecorder;
    private final ConversationMemory conversationMemory;
    /** 历史注入的预算（轮数 / 总字数 / 单条上限），见 {@link ConversationHistory}。 */
    private final ConversationHistory.Budget historyBudget;

    public AssistantWorkflowService(IntentAgent intentAgent, OrderAgent orderAgent, KnowledgeAgent knowledgeAgent,
                                    ToolDispatcher toolDispatcher, ToolRegistry toolRegistry,
                                    AssistantResponseAssembler responseAssembler, AiClientPort aiClient,
                                    PromptCatalog promptCatalog, WorkflowTraceRecorder workflowTraceRecorder,
                                    ConversationMemory conversationMemory, ConversationHistory.Budget historyBudget) {
        this.intentAgent = intentAgent;
        this.orderAgent = orderAgent;
        this.knowledgeAgent = knowledgeAgent;
        this.toolDispatcher = toolDispatcher;
        this.toolRegistry = toolRegistry;
        this.responseAssembler = responseAssembler;
        this.aiClient = aiClient;
        this.promptCatalog = promptCatalog;
        this.workflowTraceRecorder = workflowTraceRecorder;
        this.conversationMemory = conversationMemory;
        this.historyBudget = historyBudget == null ? ConversationHistory.Budget.defaults() : historyBudget;
    }

    public Flux<AssistantEvent> stream(AssistantRequest request) {
        return Flux.defer(() -> {
            // 请求没带 orderNo 时从消息里抽（用户常把订单号直接写在句子里）
            String orderNo = request.orderNo() == null || request.orderNo().isBlank()
                    ? QuestionSignals.orderNoIn(request.message())
                    : request.orderNo();
            WorkflowContext context = new WorkflowContext(request.sessionId(), request.customerId(), orderNo, request.message());
            AtomicBoolean failed = new AtomicBoolean(false);
            // 历史读取是阻塞 JDBC，必须离开 Netty 事件循环；读失败按"无历史"处理。
            return Mono.fromCallable(() -> loadHistory(context))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(history -> {
                        context.conversationHistory(history);
                        return runWorkflow(context)
                                .doOnError(ex -> failed.set(true))
                                // 以前这里只把错误塞进 SSE 事件、不落日志，排障时连堆栈都看不到
                                .doOnError(ex -> log.warn("工作流执行失败 traceId={}", context.trace().traceId(), ex))
                                .onErrorResume(ex -> Flux.just(AssistantEvent.of(AssistantEventType.ERROR, ex.getMessage(), Map.of("error", ex.getClass().getSimpleName()))))
                                .doFinally(signal -> persistAfterStream(context, signal, failed.get()));
                    });
        });
    }

    /**
     * 读取并按预算裁剪会话历史。
     *
     * <p>裁剪**只在这里做一次**：之后的 4 个提示词（intent / order / knowledge / direct-answer）
     * 拿到的都是同一个已裁剪好的列表，谁也不可能有"我以为历史只有几百字"的错觉。
     * 以前每个调用点自己渲染全量历史，谁都看不到总长度 —— 长会话下 prompt 会被旧回答撑爆。
     */
    private List<ConversationTurn> loadHistory(WorkflowContext context) {
        List<ConversationTurn> loaded = conversationMemory.recentTurns(context.sessionId(), historyBudget.maxTurns());
        List<ConversationTurn> trimmed = ConversationHistory.trim(loaded, historyBudget);
        int before = ConversationHistory.promptChars(loaded);
        int after = ConversationHistory.promptChars(trimmed);
        if (trimmed.size() < loaded.size() || after < before) {
            log.info("会话 {} 历史按预算裁剪：{} 轮/{} 字 → {} 轮/{} 字（预算 {} 轮/{} 字）",
                    context.sessionId(), loaded.size(), before, trimmed.size(), after,
                    historyBudget.maxTurns(), historyBudget.maxPromptChars());
        } else {
            log.debug("会话 {} 加载到 {} 轮历史，共 {} 字", context.sessionId(), trimmed.size(), after);
        }
        return trimmed;
    }

    /**
     * 请求结束时落库：workflow trace + （成功的轮次）会话历史。
     *
     * <p>三条硬性约束：
     * 1. doFinally 跑在 Netty 事件循环线程上，**不能在这里做阻塞 DB 写入**，所以丢给 boundedElastic；
     * 2. 落库失败绝不能冒泡成一次 500，回答已经推给用户了；
     * 3. 只有"正常完成且拿到了最终回答"的轮次才进历史 —— 失败/取消的半轮不记，避免污染上下文。
     */
    private void persistAfterStream(WorkflowContext context, SignalType signal, boolean failed) {
        WorkflowTraceStatus status = traceStatusFor(signal, failed);
        Instant completedAt = Instant.now();
        Object answer = context.recall(FINAL_ANSWER_KEY);
        String finalAnswer = answer instanceof String text ? text : null;
        boolean rememberTurn = status == WorkflowTraceStatus.COMPLETED
                && finalAnswer != null && !finalAnswer.isBlank();
        try {
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    workflowTraceRecorder.record(context, status, completedAt);
                } catch (RuntimeException ex) {
                    log.warn("workflow trace 落库失败 traceId={}", context.trace().traceId(), ex);
                }
                if (rememberTurn) {
                    try {
                        conversationMemory.recordTurn(context.sessionId(), context.customerId(),
                                context.userMessage(), finalAnswer);
                    } catch (RuntimeException ex) {
                        log.warn("会话历史写入失败 sessionId={}", context.sessionId(), ex);
                    }
                }
            });
        } catch (RuntimeException ex) {
            log.warn("无法调度落库任务 traceId={}", context.trace().traceId(), ex);
        }
    }

    /**
     * 终止信号 → trace 最终状态。抽成纯函数便于单测覆盖四种组合。
     *
     * <p>注意：工作流里的异常会被 {@code onErrorResume} 兜成 error 事件，此时信号是 ON_COMPLETE，
     * 所以必须要靠 {@code failed} 标记才能识别失败。
     */
    static WorkflowTraceStatus traceStatusFor(SignalType signal, boolean failed) {
        return switch (signal) {
            case CANCEL -> WorkflowTraceStatus.CANCELLED;
            case ON_ERROR -> WorkflowTraceStatus.FAILED;
            default -> failed ? WorkflowTraceStatus.FAILED : WorkflowTraceStatus.COMPLETED;
        };
    }

    /**
     * 主链路（2026-11-11 重构）：**先问模型"要不要工具"，由模型决定走哪条路**。
     *
     * <p>重构原因：原来 `shouldUseAgentWorkflow`（关键词）是闸门 —— 它不命中就根本不会调用模型，
     * 于是「这个能退吗」「这单我不要了」这类自然说法被直接挡在门外，统一得到"我无法操作"。
     * 实测 7 条口语里 6 条进不了工作流，"模型优先"只对闸门内的句子生效。
     *
     * <p>现在的分工：
     * <ol>
     *   <li><b>纯闲聊</b>（{@link QuestionSignals#isPureChitchat}：短句 + 寒暄白名单 + 无业务信号）→ 直答快路径，
     *       省掉一次模型往返；</li>
     *   <li><b>其余全部</b>先跑 IntentAgent：模型给出工具调用 → 执行工具 + 上下文分析 + 组装回答；</li>
     *   <li>模型说"不需要工具" → 落到直答路径（先检索再作答），而不是留一句"没能给出有效回答"。</li>
     * </ol>
     */
    private Flux<AssistantEvent> runWorkflow(WorkflowContext context) {
        if (QuestionSignals.isPureChitchat(context.userMessage())) {
            return runDirectQwen(context);
        }
        return Flux.concat(
                Flux.just(
                        AssistantEvent.of(AssistantEventType.WORKFLOW_STARTED, "Workflow started", Map.of(
                                "traceId", context.trace().traceId(),
                                "sessionId", context.sessionId())),
                        AssistantEvent.of(AssistantEventType.AGENT_STARTED, "Intent Agent 正在识别意图", Map.of("agent", "IntentAgent"))),
                runIntentAgent(context).flatMapMany(intent -> intent.toolCalls().isEmpty()
                        ? Flux.concat(Flux.just(intentCompletedEvent(intent)), emptyToolIntentTail(context))
                        : Flux.concat(runTools(context, intent),
                                Flux.defer(() -> runContextAgentsAndFinalAnswer(context)))));
    }

    /**
     * 意图 Agent 没给工具时走哪里：
     * <ul>
     *   <li>有"品类候选"（用户只说了品类）→ 渲染候选清单 + 反问（确定性文案，不经模型改写）；</li>
     *   <li>否则 → 直答（先检索再作答）。</li>
     * </ul>
     */
    private Flux<AssistantEvent> emptyToolIntentTail(WorkflowContext context) {
        String candidates = responseAssembler.purchaseCandidatesText(context);
        if (candidates != null && !candidates.isBlank()) {
            return streamText(context, candidates);
        }
        return runDirectQwen(context);
    }

    /** Intent Agent 完成事件（工具路径与直答兜底路径都要发，前端"工作流面板"靠它收尾）。 */
    private AssistantEvent intentCompletedEvent(IntentResult intent) {
        return AssistantEvent.of(AssistantEventType.AGENT_COMPLETED, "Intent Agent 已完成", Map.of(
                "agent", "IntentAgent",
                "intent", intent.intent().name(),
                "title", intent.title(),
                "priority", intent.priority().name(),
                "confidence", intent.confidence()));
    }

    private Mono<IntentResult> runIntentAgent(WorkflowContext context) {
        return Mono.fromCallable(() -> {
                    WorkflowStep intentStep = context.trace().startStep("IntentAgent", "agent", Map.of("message", context.userMessage()));
                    IntentResult intent = intentAgent.analyze(context, toolMetadataJson());
                    context.intent(intent);
                    context.trace().complete(intentStep, Map.of("intent", intent.intent().name(), "confidence", intent.confidence()));
                    return intent;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AssistantEvent> runTools(WorkflowContext context, IntentResult intent) {
        List<AssistantEvent> events = new ArrayList<>();
        events.add(intentCompletedEvent(intent));
        if (intent.toolCalls().isEmpty()) {
            return Flux.fromIterable(events);
        }
        events.add(AssistantEvent.of(AssistantEventType.TOOL_CALL, "AI requested tool calls", Map.of("toolCalls", intent.toolCalls())));
        events.add(AssistantEvent.of(AssistantEventType.AGENT_STARTED, "ToolDispatcher 正在执行工具", Map.of("toolCount", intent.toolCalls().size())));
        return Flux.concat(
                Flux.fromIterable(events),
                Mono.fromCallable(() -> {
                            WorkflowStep toolStep = context.trace().startStep("ToolDispatcher", "tool", Map.of("count", intent.toolCalls().size()));
                            List<ToolResult> results = toolDispatcher.dispatchAll(intent.toolCalls(), context);
                            context.trace().complete(toolStep, Map.of("results", results));
                            return results;
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(results -> Flux.fromIterable(toolEvents(results))));
    }

    /**
     * 上下文 Agent + 最终回答。
     *
     * <p>回答正文是**真流式**的：知识回答由 {@code KnowledgeAgent.streamAnswer} 逐段产出（DeepSeek 逐 token），
     * 收到一段就推一个 {@code final} 增量 —— 首字延迟从"整段生成完"（4~15s）降到"第一个 token"。
     *
     * <p>并行性：订单分析与知识生成必须同时进行，但 {@code Flux.concat} 是**懒订阅** ——
     * 如果只在末尾 `orderAnalysis.flatMapMany(...)` 里消费它，它要等知识流结束才开始跑（串行，慢一倍）。
     * 所以用 {@code cache()} 缓存结果 + 一个"只为启动"的空分支（{@code orderKickoff}）提前订阅它。
     */
    private Flux<AssistantEvent> runContextAgentsAndFinalAnswer(WorkflowContext context) {
        boolean needsOrderAnalysis = hasSuccessfulToolResult(context, ToolNames.QUERY_ORDER);
        boolean needsKnowledgeAnswer = hasSuccessfulToolResult(context, ToolNames.SEARCH_KNOWLEDGE);
        return Flux.concat(
                contextAgentStartedEvents(needsOrderAnalysis, needsKnowledgeAnswer),
                answerStream(context, needsOrderAnalysis, needsKnowledgeAnswer));
    }

    private Flux<AssistantEvent> answerStream(WorkflowContext context, boolean needsOrderAnalysis, boolean needsKnowledgeAnswer) {
        if (!needsOrderAnalysis && !needsKnowledgeAnswer) {
            // 没有上下文 Agent：正文全是确定性文案（确认卡片 / 工单 / 失败提示），按句推送即可
            return Flux.concat(
                    streamText(context, responseAssembler.assemble(context, "", "")),
                    traceEvent(context));
        }

        Mono<String> orderAnalysis = runOrderAgent(context, needsOrderAnalysis).cache();
        // 只为"提前订阅、让订单分析与知识流并行"，本身不产出事件
        Flux<AssistantEvent> orderKickoff = orderAnalysis.thenMany(Flux.<AssistantEvent>empty());

        StringBuilder streamed = new StringBuilder();
        Flux<AssistantEvent> body = Flux.concat(
                // RAG 来源先点亮（不等模型），用户能立刻看到"检索到了什么"
                knowledgeSourceEvents(context),
                streamKnowledgeTokens(context, needsKnowledgeAnswer, streamed),
                orderAnalysis.flatMapMany(order -> Flux.concat(
                        // 必须 defer：Flux.just(...) 在**装配时**就求值，会拿到"尾巴还没追加"的半截正文
                        Flux.defer(() -> Flux.just(contextCompletedEvent(context, needsOrderAnalysis, needsKnowledgeAnswer, order, streamed.toString()))),
                        streamAnswerTail(context, order, streamed),
                        Flux.defer(() -> Flux.just(answerDone(context, streamed))))));
        return Flux.merge(orderKickoff, body).concatWith(traceEvent(context));
    }

    /** 知识回答的增量流：边收边推 {@code final(delta)}，同时在 trace 里记录耗时与全文长度。 */
    private Flux<AssistantEvent> streamKnowledgeTokens(WorkflowContext context, boolean enabled, StringBuilder streamed) {
        if (!enabled) {
            return Flux.empty();
        }
        WorkflowStep knowledgeStep = context.trace().startStep("KnowledgeAgent", "agent", Map.of("tool", ToolNames.SEARCH_KNOWLEDGE));
        return knowledgeAgent.streamAnswer(context)
                // 默认（非真流式）实现里 complete() 是阻塞调用，必须离开事件循环
                .subscribeOn(Schedulers.boundedElastic())
                .filter(delta -> !delta.isEmpty())
                .doOnNext(streamed::append)
                .map(delta -> AssistantEvent.of(AssistantEventType.FINAL, delta, Map.of("delta", delta, "done", false)))
                .doOnComplete(() -> context.trace().complete(knowledgeStep, Map.of("summary", streamed.toString(), "chars", streamed.length())))
                .doOnError(error -> context.trace().fail(knowledgeStep, Map.of("error", String.valueOf(error.getMessage()))));
    }

    /**
     * 追加知识结论**之后**的确定性小节（订单情况 / 需要你确认 / 工单 / 失败提示）。
     *
     * <p>真流式下这些内容在流结束时才算得出来，所以单独按句推送；
     * 若模型一个字都没吐，这里补上兜底话术，避免用户看到空白回答。
     */
    private Flux<AssistantEvent> streamAnswerTail(WorkflowContext context, String orderAnalysis, StringBuilder streamed) {
        String tail = responseAssembler.sectionsAfterKnowledge(context, orderAnalysis);
        if (streamed.length() == 0 && tail.isEmpty()) {
            tail = responseAssembler.noAnswerHint();
        }
        if (tail.isEmpty()) {
            return Flux.empty();
        }
        // 与 assemble() 的排版保持一致：小节之间空一行（否则会渲染成"结论。**订单情况**"挤在同一行）
        String payload = streamed.length() > 0 ? "\n\n" + tail : tail;
        return Flux.fromIterable(StreamChunks.sentences(payload))
                .delayElements(Duration.ofMillis(90))
                .map(chunk -> {
                    streamed.append(chunk);
                    return AssistantEvent.of(AssistantEventType.FINAL, chunk, Map.of("delta", chunk, "done", false));
                });
    }

    /** 收尾：把累计正文（含尾巴）落进上下文供会话历史使用，并推 done=true 的最后一帧。 */
    private AssistantEvent answerDone(WorkflowContext context, StringBuilder streamed) {
        String full = streamed.toString();
        if (full.isBlank()) {
            full = responseAssembler.noAnswerHint();
        }
        if (!full.endsWith("\n")) {
            full = full + "\n";
        }
        context.remember(FINAL_ANSWER_KEY, full);
        return AssistantEvent.of(AssistantEventType.FINAL, full, Map.of("fullText", full, "done", true));
    }

    private Flux<AssistantEvent> contextAgentStartedEvents(boolean needsOrderAnalysis, boolean needsKnowledgeAnswer) {
        List<AssistantEvent> events = new ArrayList<>();
        if (needsOrderAnalysis || needsKnowledgeAnswer) {
            List<String> agents = new ArrayList<>();
            if (needsOrderAnalysis) {
                agents.add("OrderAgent");
                events.add(AssistantEvent.of(AssistantEventType.AGENT_STARTED, "Order Agent 正在分析订单上下文", Map.of("agent", "OrderAgent")));
            }
            if (needsKnowledgeAnswer) {
                agents.add("KnowledgeAgent");
                events.add(AssistantEvent.of(AssistantEventType.AGENT_STARTED, "Knowledge Agent 正在检索并生成回答", Map.of("agent", "KnowledgeAgent")));
            }
            events.add(0, AssistantEvent.of(AssistantEventType.AGENT_STARTED, "上下文 Agent 正在分析", Map.of("agents", agents)));
        }
        return Flux.fromIterable(events);
    }

    /**
     * 上下文 Agent 的完成事件。
     *
     * <p>注意顺序变了（2026-11-04 真流式）：以前是「agent_completed 带上回答全文 → 再按下发」，
     * 现在回答是流式产出的，完成事件只能在流结束时发 —— 前端卡片状态语义不变（running → done），
     * 但 `knowledgeAnswer` 字段是**已累加全文**，不是模型原样单帧。
     */
    private AssistantEvent contextCompletedEvent(WorkflowContext context, boolean needsOrderAnalysis,
                                                 boolean needsKnowledgeAnswer, String orderAnalysis, String knowledgeAnswer) {
        return AssistantEvent.of(AssistantEventType.AGENT_COMPLETED, "上下文 Agent 已完成", Map.of(
                "orderAnalysis", orderAnalysis == null ? "" : orderAnalysis,
                "knowledgeAnswer", knowledgeAnswer == null ? "" : knowledgeAnswer,
                "streamed", needsKnowledgeAnswer));
    }

    private Flux<AssistantEvent> knowledgeSourceEvents(WorkflowContext context) {
        if (context.knowledgeResults().isEmpty()) {
            return Flux.empty();
        }
        return Flux.just(AssistantEvent.of(AssistantEventType.KNOWLEDGE_SOURCES, "RAG sources retrieved",
                Map.of("sources", context.knowledgeResults())));
    }

    private Flux<AssistantEvent> traceEvent(WorkflowContext context) {
        return Flux.just(AssistantEvent.of(AssistantEventType.TRACE, "Workflow trace", Map.of(
                "traceId", context.trace().traceId(),
                "steps", context.trace().steps())));
    }

    private Mono<String> runOrderAgent(WorkflowContext context, boolean enabled) {
        if (!enabled) {
            return Mono.just("");
        }
        return Mono.fromCallable(() -> {
                    WorkflowStep orderStep = context.trace().startStep("OrderAgent", "agent", Map.of("tool", ToolNames.QUERY_ORDER));
                    String orderAnalysis = orderAgent.analyze(context);
                    context.trace().complete(orderStep, Map.of("summary", orderAnalysis));
                    return orderAnalysis;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private List<AssistantEvent> toolEvents(List<ToolResult> results) {
        List<AssistantEvent> events = new ArrayList<>();
        events.add(AssistantEvent.of(AssistantEventType.TOOL_RESULT, "ToolDispatcher 已执行工具", Map.of("results", results)));
        results.stream()
                .filter(result -> result.success() && "CREATE_TICKET".equals(result.tool()))
                .findFirst()
                .ifPresent(result -> events.add(AssistantEvent.of(AssistantEventType.TICKET_CREATED, "Ticket created", Map.of("ticket", result.data()))));
        results.stream()
                .filter(result -> result.success() && "UPDATE_TICKET".equals(result.tool()))
                .findFirst()
                .ifPresent(result -> events.add(AssistantEvent.of(AssistantEventType.TICKET_UPDATED, "Ticket updated", Map.of("ticket", result.data()))));
        results.stream()
                .filter(result -> result.success() && ToolNames.CREATE_ORDER.equals(result.tool()))
                // 一件商品一张待确认单：**每个成功的 CREATE_ORDER 都要发一个事件**，
                // 以前用 findFirst() 只发第一个 —— 多商品下单时第二个卡片根本不会出现（§5.4.2）
                .forEach(result -> events.add(AssistantEvent.of(
                        AssistantEventType.ORDER_CONFIRMATION_REQUIRED, "Order confirmation required",
                        Map.of("confirmation", result.data()))));
        return events;
    }

    private boolean hasSuccessfulToolResult(WorkflowContext context, String toolName) {
        return context.toolResults().stream()
                .anyMatch(result -> result.success() && toolName.equals(result.tool()));
    }

    /**
     * 直答路径：**先查知识库，再真流式作答**。
     *
     * <p>两件事：
     * <ol>
     *   <li><b>前置检索</b>（2026-11-04 新增）：以前这条路径完全不看知识库，需要资料的问法只能靠模型自身知识，
     *       于是可能编。现在先跑一次本地 BM25 检索（无模型成本），命中就把片段塞进提示词的 `{{sources}}` 块，
     *       并推 `knowledge_sources` 事件让右侧 RAG 面板可见；没命中则 `{{sources}}` 为空，提示词要求按通用知识作答。</li>
     *   <li><b>真流式</b>：直接消费 {@code aiClient.stream(...)}，DeepSeek 上就是 token 级推送，
     *       其它适配器走端口默认实现（整段切句，观感不变）。</li>
     * </ol>
     */
    private Flux<AssistantEvent> runDirectQwen(WorkflowContext context) {
        // 检索是阻塞的（JDBC + 可能一次 embedding HTTP）：先挪到 boundedElastic 上跑，
        // 再回到响应式链路里拼提示词 —— 直接在事件循环上调用会让语义向量适配器被 Reactor 拦下并静默降级。
        return Mono.fromCallable(() -> retrieveKnowledgeForDirectAnswer(context))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(hasSources -> {
                    PromptTemplate prompt = promptCatalog.get(PromptIds.DIRECT_ANSWER);
                    String history = ConversationTurn.toPromptBlock(context.conversationHistory());
                    AiRequest request = new AiRequest(
                            "DirectQwen",
                            prompt.system(),
                            prompt.user(Map.of(
                                    "userMessage", context.userMessage(),
                                    "history", history,
                                    "sources", hasSources ? knowledgeAgent.sourcesBlock(context) : "")),
                            Map.of("customerId", context.customerId(),
                                    "orderNo", context.orderNo() == null ? "" : context.orderNo(),
                                    "userMessage", context.userMessage()),
                            false);
                    StringBuilder streamed = new StringBuilder();
                    Flux<AssistantEvent> tokens = aiClient.stream(request)
                            // 默认实现里 complete() 是阻塞 HTTP，必须离开 Netty 事件循环
                            .subscribeOn(Schedulers.boundedElastic())
                            .map(AiStreamEvent::delta)
                            .filter(delta -> !delta.isEmpty())
                            .doOnNext(streamed::append)
                            .map(delta -> AssistantEvent.of(AssistantEventType.FINAL, delta,
                                    Map.of("delta", delta, "done", false)))
                            .concatWith(Mono.fromSupplier(() -> answerDone(context, streamed)));
                    // 命中知识库时先点亮 RAG 面板（和走工作流时的顺序一致）
                    return hasSources ? Flux.concat(knowledgeSourceEvents(context), tokens) : tokens;
                });
    }

    /** 直答前的检索（阻塞 JDBC/内存计算），失败按"无资料"处理，并在 trace 里留一步。 */
    private boolean retrieveKnowledgeForDirectAnswer(WorkflowContext context) {
        WorkflowStep step = context.trace().startStep("SearchKnowledge", "tool",
                Map.of("query", context.userMessage(), "stage", "direct-answer"));
        boolean hit;
        try {
            hit = knowledgeAgent.retrieveKnowledge(context);
        } catch (RuntimeException ex) {
            context.trace().fail(step, Map.of("error", String.valueOf(ex.getMessage())));
            log.warn("直答路径检索异常，按无资料继续：{}", ex.getMessage(), ex);
            return false;
        }
        context.trace().complete(step, Map.of("hits", context.knowledgeResults().size(), "used", hit));
        return hit;
    }

    /**
     * ⚠ **已不参与运行时路由（2026-11-11）**：路由改为"先问模型要不要工具"（见 {@link #runWorkflow}）。
     * 保留它是作为**关键词覆盖度的诊断口径**（单测与影响面快照仍在用），不要再拿它做分支判断。
     *
     * <p>历史说明：原先是"只有命中业务的才进 Agent 工作流，其余直连 AI"。
     *
     * <p>**判定全部来自 {@link QuestionSignals}**（唯一关键词表），本类不写关键词字面量。
     * 2026-11-05 起连这九路的**组合**也搬进了 {@link QuestionSignals#needsAgentWorkflow}：
     * 组合逻辑留在服务里时，"哪几个信号决定路由"只在这个文件里可见，改表的人看不到全貌。
     */
    static boolean shouldUseAgentWorkflow(String message) {
        return QuestionSignals.needsAgentWorkflow(message);
    }

    /** 已经是完整文本（确定性文案）：按句切片推送，末帧带 fullText + done=true。 */
    private Flux<AssistantEvent> streamText(WorkflowContext context, String text) {
        List<String> chunks = StreamChunks.sentences(text);
        if (chunks.isEmpty()) {
            context.remember(FINAL_ANSWER_KEY, text);
            return Flux.just(AssistantEvent.of(AssistantEventType.FINAL, text, Map.of("delta", text, "done", true)));
        }
        StringBuilder full = new StringBuilder();
        return Flux.fromIterable(chunks)
                .delayElements(Duration.ofMillis(90))
                .map(chunk -> {
                    full.append(chunk);
                    return AssistantEvent.of(AssistantEventType.FINAL, chunk, Map.of("delta", chunk, "done", false));
                })
                .concatWith(Mono.fromSupplier(() -> {
                    // 完整回答存进上下文，供 doFinally 写入会话历史
                    context.remember(FINAL_ANSWER_KEY, full.toString());
                    return AssistantEvent.of(AssistantEventType.FINAL, full.toString(), Map.of("fullText", full.toString(), "done", true));
                }));
    }

    private String toolMetadataJson() {
        return toolRegistry.metadata().stream()
                .map(this::formatTool)
                .collect(Collectors.joining("\n"));
    }

    private String formatTool(ToolMetadata metadata) {
        return "- %s: %s input=%s result=%s".formatted(metadata.name(), metadata.description(), metadata.inputSchema(), metadata.resultSchema());
    }
}
