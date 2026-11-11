package com.example.aiticketassistant.application.agent;

import com.example.aiticketassistant.application.catalog.BudgetPlan;
import com.example.aiticketassistant.application.conversation.ConversationTurn;
import com.example.aiticketassistant.application.prompt.PromptCatalog;
import com.example.aiticketassistant.application.tool.tools.PlanBudgetTool;
import com.example.aiticketassistant.application.prompt.PromptIds;
import com.example.aiticketassistant.application.prompt.PromptTemplate;
import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchRepository;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchResult;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 知识 Agent：把 RAG 检索结果整理成**给用户看**的结论。
 *
 * <p>两个关键处理（都为了"答案别乱"）：
 * 1. **精度优先排序**：用户问题里出现数字（价格 699、商品编号 CLOTH-XXX-003）时，优先保留内容命中了这些数字的片段，
 *    再按分数排序 —— 否则 BM25 会把同价位附近的其它商品一起混进来；
 * 2. **只喂干净文本**：给模型的每条来源只保留「标题：内容」，去掉内部 URI 与 score，避免它们被抄进最终回答。
 */
@Component
public class KnowledgeAgent {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeAgent.class);
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
    /** 最多引用几条来源，多了用户读不过来。 */
    private static final int MAX_SOURCES = 3;
    /**
     * 单条来源正文的兜底上限。
     *
     * <p>注意别再调小：整张价格表片段本身就有 300+ 字，截断后模型会看到"表格到一半就没了"，
     * 于是回答里出现"价格表被截断，无法确认"这类免责话术（2026-11-03 实测踩过）。
     * 片段该多大由迁移/切分负责，这里只防超长文本把上下文撑爆。
     *
     * <p>2026-11-03 从 400 调到 1200：目录从 10 件扩到 30 件后，`CHK-CATALOG-CLOTHING-ALL`
     * （整张价格表）约 950 字，400 会把它砍掉一半 —— 症状和当初一模一样（"表不全 → 结论错"）。
     *
     * <p>2026-11-05 目录 30 → 60 件（V10），整表涨到 **1917 字**，于是再抬到 2600。
     * 这次是**按预算算过的**而不是顺手加：1917 字≈1.3k token，远小于提示词预算；
     * 真正该分册的阈值是"整表超过 ~4000 字"（那时分册才有收益，而且 `PriceCatalogService`
     * 与 `SeededProductCatalogConsistencyTest` 都是按"多条汇总片段并集"设计的，分册不会破坏它们）。
     */
    private static final int MAX_CONTENT_LENGTH = 2600;

    /**
     * 直答路径前置检索的最低分（<b>混合检索的绝对融合分</b>：0.7×BM25饱和 + 0.3×余弦，见
     * {@code HybridKnowledgeSearchRepository}）。
     *
     * <p>分界线和**向量那一路用的是哪个模型**强相关，换 embedding 模型必须重标。两次标定（34 条片段）：
     * <pre>
     * 2026-11-04 本地哈希向量（EMBEDDING_PROVIDER=hash），分界线 0.08~0.12 → 取 0.10
     * 应过滤：你好 0.041 / 谢谢明白了 0.042 / 可以开发票吗 0.052 / 运费多少 0.075 / 帮我写一首诗 0.081
     * 应保留：支持哪些支付方式 0.122 / 订单显示 FAILED 是什么意思 0.176 / 外套多少钱 0.259 /
     *        七百块的衣服 0.367 / 帽衫多少钱 0.390 / 裙子有哪些 0.527 / 支付成功但订单没生成 0.631
     *
     * 2026-11-05 阿里云 qwen3.7-text-embedding-flash，分界线移到 0.105~0.312 → 取 0.12
     * 应过滤：你是谁 0.077 / 今天天气怎么样 0.091 / 帮我翻译一句英文 0.087 / 明天股票会涨吗 0.087 /
     *        你们公司几点上班 0.099 / Windows 怎么装 JDK 0.100 / 这个系统用的什么框架 0.102 / 讲个笑话 0.105
     * 应保留：退款要几天 0.312 / 价格刚好699的衣服是什么 0.499 / 藏青帆布鞋多少钱 0.559 /
     *        支付成功后订单没生成怎么办 0.581 / 我的衣服比699便宜的有哪几种 0.620
     * 语义向量把分数整体抬高了一点点（噪声 0.08 → 0.10），0.10 会漏进 2 条无关片段，所以抬到 0.12。
     * </pre>
     * **换语料或换 embedding 模型都要重标**：这个数字依赖"片段有多长、有多少条、向量模型是谁"，
     * 不是普适常数。噪声还有第二道兜底 —— 提示词明确要求"资料不相关就忽略、不要写依据"。
     */
    static final double MIN_DIRECT_ANSWER_SCORE = 0.12;
    /** 直答路径最多带几条来源：再多就是噪音，也白烧 token。 */
    static final int DIRECT_ANSWER_SOURCES = 3;

    private final AiClientPort aiClient;
    private final PromptCatalog promptCatalog;
    private final KnowledgeSearchRepository searchRepository;

    public KnowledgeAgent(AiClientPort aiClient, PromptCatalog promptCatalog, KnowledgeSearchRepository searchRepository) {
        this.aiClient = aiClient;
        this.promptCatalog = promptCatalog;
        this.searchRepository = searchRepository;
    }

    /** 没有任何可信来源时给用户的固定说明（不进模型，避免它自己编）。 */
    static final String NO_SOURCES_ANSWER = "知识库里没有找到与这个问题直接相关的资料，建议补充商品编号或订单号，或转人工确认。";

    /**
     * 直答路径的前置检索：**回答之前先把知识库翻一遍**。
     *
     * <p>背景：直答路径（闲聊、通用售后）以前完全不查知识库，需要资料的问法只能靠模型自身知识，于是可能编。
     * 现在先检索，命中就把片段放进上下文（供提示词与前端 RAG 面板使用），没命中就按通用知识回答。
     *
     * <p>检索失败不影响回答：日志里留痕，按"没命中"处理。
     *
     * @return true 表示找到了够相关的资料（调用方应把来源喂进提示词）
     */
    public boolean retrieveKnowledge(WorkflowContext context) {
        String query = context.userMessage();
        if (query == null || query.isBlank() || searchRepository == null) {
            return false;
        }
        try {
            List<KnowledgeSearchResult> relevant = searchRepository.search(query, DIRECT_ANSWER_SOURCES).stream()
                    .filter(result -> result.finalScore() >= MIN_DIRECT_ANSWER_SCORE)
                    .toList();
            if (relevant.isEmpty()) {
                return false;
            }
            context.knowledgeResults().addAll(relevant);
            return true;
        } catch (RuntimeException ex) {
            log.warn("直答路径检索知识库失败，按无资料处理：{}", ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * 把上下文里的来源渲染成提示词用的纯文本（「- 标题：内容」，无内部 URI / score）。
     *
     * <p>供两处共用：知识 Agent 自己的提示词，和直答路径的 `{{sources}}` 块。
     * 返回空串表示"没有资料"，调用方据此让模型改用通用知识作答。
     */
    public String sourcesBlock(WorkflowContext context) {
        return render(selectSources(context));
    }

    /**
     * 逐段生成回答（真流式入口）。
     *
     * <p>工作流用它把模型输出**边生成边推**给前端；来源筛选、提示词拼装与
     * {@link #synthesize} 完全共用同一套逻辑，区别只在"拿整段"还是"拿增量"。
     * 没有命中来源时不调用模型，直接吐一句固定说明（一个分片即可）。
     */
    public Flux<String> streamAnswer(WorkflowContext context) {
        List<KnowledgeSearchResult> sources = selectSources(context);
        if (sources.isEmpty()) {
            return Flux.just(NO_SOURCES_ANSWER);
        }
        return aiClient.stream(aiRequest(context, sources))
                .map(AiStreamEvent::delta);
    }

    /** 一次性拿整段回答（非流式调用点 / 回归测试用；工作流走 {@link #streamAnswer}）。 */
    public String synthesize(WorkflowContext context) {
        List<KnowledgeSearchResult> sources = selectSources(context);
        if (sources.isEmpty()) {
            return NO_SOURCES_ANSWER;
        }
        return aiClient.complete(aiRequest(context, sources)).text();
    }

    /** 两种调用方式共用的请求构造：只喂「标题：内容」的干净文本，不带内部 URI 与 score。 */
    private AiRequest aiRequest(WorkflowContext context, List<KnowledgeSearchResult> sources) {
        String rendered = render(sources);

        PromptTemplate prompt = promptCatalog.get(PromptIds.KNOWLEDGE_AGENT);
        String user = prompt.user(Map.of(
                "userMessage", context.userMessage(),
                "sources", rendered,
                "planResult", planBlock(context),
                "history", ConversationTurn.toPromptBlock(context.conversationHistory())));
        return new AiRequest("KnowledgeAgent", prompt.system(), user,
                Map.of("sources", rendered, "userMessage", context.userMessage()), false);
    }

    /** 来源渲染的唯一口径（知识 Agent 与直答路径共用）。 */
    private static String render(List<KnowledgeSearchResult> sources) {
        return sources.stream()
                .map(result -> "- %s：%s".formatted(result.chunk().title(), summarize(result.chunk().content())))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 预算测算结果（由 {@code PLAN_BUDGET} 工具算出）。没有测算时给一句明确说明，
     * 避免模型把"空"理解成"自己算一遍"。
     */
    private String planBlock(WorkflowContext context) {
        Object plan = context.recall(PlanBudgetTool.PLAN_CONTEXT_KEY);
        if (plan instanceof BudgetPlan budgetPlan) {
            return budgetPlan.toPromptBlock().trim();
        }
        return "（本问题不涉及预算测算；不要自行计算件数或合计。）";
    }

    private List<KnowledgeSearchResult> selectSources(WorkflowContext context) {
        List<KnowledgeSearchResult> all = context.knowledgeResults();
        if (all.isEmpty()) {
            return List.of();
        }
        Set<String> queryNumbers = numbersIn(context.userMessage());
        List<KnowledgeSearchResult> precise = queryNumbers.isEmpty()
                ? List.of()
                : all.stream().filter(result -> containsAny(result.chunk().content(), queryNumbers)).toList();
        List<KnowledgeSearchResult> candidates = precise.isEmpty() ? all : precise;

        // 按 chunk id 去重后按分数排序，取前 N 条
        Map<String, KnowledgeSearchResult> deduped = new LinkedHashMap<>();
        candidates.stream()
                .sorted(Comparator.comparingDouble(KnowledgeSearchResult::finalScore).reversed())
                .forEach(result -> deduped.putIfAbsent(result.chunk().id(), result));
        return deduped.values().stream().limit(MAX_SOURCES).toList();
    }

    private Set<String> numbersIn(String text) {
        Set<String> numbers = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return numbers;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    private boolean containsAny(String content, Set<String> numbers) {
        if (content == null) {
            return false;
        }
        return numbers.stream().anyMatch(content::contains);
    }

    private static String summarize(String content) {
        if (content == null) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_CONTENT_LENGTH ? flat : flat.substring(0, MAX_CONTENT_LENGTH) + "…";
    }
}
