package com.example.aiticketassistant.application.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.aiticketassistant.application.workflow.WorkflowContext;
import com.example.aiticketassistant.domain.knowledge.KnowledgeChunk;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchRepository;
import com.example.aiticketassistant.domain.knowledge.KnowledgeSearchResult;
import com.example.aiticketassistant.infrastructure.prompt.ClasspathPromptCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 直答路径前置检索的判定（{@link KnowledgeAgent#retrieveKnowledge}）。
 *
 * <p>为什么单独测这一段：它是"直答会不会编"的开关 ——
 * 命中就带资料作答（可核查），没命中就按通用知识（可能不准，但至少提示词要求它别编）。
 * 门槛调高会把"问法不标准但资料在库里"的问题推回瞎编，调低会让闲聊也硬塞一段无关资料。
 */
class KnowledgeAgentRetrievalTest {
    private static final AiClientPort NO_AI = request -> AiResponse.fallback("");

    private static KnowledgeAgent agent(KnowledgeSearchRepository repository) {
        return new KnowledgeAgent(NO_AI, new ClasspathPromptCatalog(), repository);
    }

    private static KnowledgeSearchResult hit(String id, String title, String content, double score) {
        return new KnowledgeSearchResult(
                new KnowledgeChunk(id, "DOC", title, "PAYMENT", "internal://kb/" + id, content),
                0.0, score, score, "bm25-local");
    }

    private static WorkflowContext context(String message) {
        return new WorkflowContext("s", "CUST-1001", null, message);
    }

    @Test
    void keepsOnlyHitsAboveTheRelevanceFloor() {
        KnowledgeSearchRepository repository = mock(KnowledgeSearchRepository.class);
        when(repository.search(anyString(), anyInt())).thenReturn(List.of(
                hit("CHK-PAY-001-1", "订单状态说明", "订单 FAILED 表示业务处理失败。", 0.30),
                hit("CHK-CATALOG-X", "服装商品目录：卫衣", "商品编号 CLOTH-HOODIE-004，价格 ¥259.00。", 0.06)));

        WorkflowContext context = context("订单显示 FAILED 是什么意思");
        boolean hitFound = agent(repository).retrieveKnowledge(context);

        assertThat(hitFound).isTrue();
        assertThat(context.knowledgeResults())
                .as("0.06 的弱命中属于噪音（真机噪声区间 0.04~0.08），不该塞进提示词")
                .extracting(result -> result.chunk().id())
                .containsExactly("CHK-PAY-001-1");
    }

    @Test
    void returnsFalseWhenNothingClearsTheFloor() {
        KnowledgeSearchRepository repository = mock(KnowledgeSearchRepository.class);
        when(repository.search(anyString(), anyInt())).thenReturn(List.of(
                hit("CHK-ORDER-001-1", "订单状态说明", "订单 FAILED 表示业务处理失败。", 0.08)));

        WorkflowContext context = context("今天天气怎么样");

        assertThat(agent(repository).retrieveKnowledge(context)).isFalse();
        assertThat(context.knowledgeResults()).isEmpty();
    }

    @Test
    void blankMessageNeverTriggersRetrieval() {
        KnowledgeSearchRepository repository = mock(KnowledgeSearchRepository.class);

        assertThat(agent(repository).retrieveKnowledge(context("   "))).isFalse();
        verify(repository, never()).search(anyString(), anyInt());   // 空消息不该白查一次
    }

    @Test
    void retrievalFailureFallsBackToNoSourcesInsteadOfBreakingTheAnswer() {
        KnowledgeSearchRepository repository = mock(KnowledgeSearchRepository.class);
        when(repository.search(anyString(), anyInt())).thenThrow(new IllegalStateException("db down"));

        WorkflowContext context = context("怎么申请退款");

        assertThat(agent(repository).retrieveKnowledge(context)).isFalse();
        assertThat(context.knowledgeResults()).isEmpty();
    }

    @Test
    void sourcesBlockRendersTitlesAndContentWithoutInternalDetails() {
        KnowledgeSearchRepository repository = mock(KnowledgeSearchRepository.class);
        when(repository.search(anyString(), anyInt())).thenReturn(List.of(
                hit("CHK-PAY-001-1", "订单状态说明", "订单 FAILED 表示业务处理失败。", 0.30)));

        WorkflowContext context = context("订单 FAILED 是什么意思");
        KnowledgeAgent agent = agent(repository);
        agent.retrieveKnowledge(context);

        assertThat(agent.sourcesBlock(context))
                .isEqualTo("- 订单状态说明：订单 FAILED 表示业务处理失败。");
    }
}
