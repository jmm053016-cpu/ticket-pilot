package com.example.aiticketassistant.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.application.agent.AiRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * fallback 规则引擎的回归测试。
 *
 * <p>核心防线：**只能拿「用户当前这句话」做关键词判断**。提示词里含会话历史与检索来源，
 * 若用整个 prompt 判断，历史里出现过的「退款」会把「价格刚好699的衣服是什么」劫持成退款答复。
 */
class FallbackAiClientAdapterTest {
    private final FallbackAiClientAdapter adapter = new FallbackAiClientAdapter();

    private AiRequest knowledgeRequest(String userPrompt, String userMessage) {
        Map<String, Object> context = userMessage == null
                ? Map.of("sources", "- 服装商品目录：羊毛混纺大衣：价格 ¥699.00")
                : Map.of("sources", "- 服装商品目录：羊毛混纺大衣：价格 ¥699.00", "userMessage", userMessage);
        return new AiRequest("KnowledgeAgent", "system", userPrompt, context, false);
    }

    @Test
    void historyMentioningRefundDoesNotHijackTheAnswer() {
        String promptWithRefundHistory = ""
                + "Previous conversation (may be empty):\n"
                + "用户: 你好\n"
                + "助手: 这是普通咨询，如果要查询你的订单、账户或执行下单/退款等业务动作，需要进入业务流程。\n\n"
                + "User question: 价格刚好699的衣服是什么\n"
                + "Retrieved sources:\n"
                + "- 服装商品目录：羊毛混纺大衣：价格 ¥699.00\n";

        String answer = adapter.complete(knowledgeRequest(promptWithRefundHistory, "价格刚好699的衣服是什么")).text();

        assertThat(answer).doesNotContain("退款问题需先确认");
        assertThat(answer).contains("根据知识库");
    }

    @Test
    void refundQuestionStillGetsRefundGuidance() {
        String answer = adapter.complete(knowledgeRequest(
                "User question: 怎么申请退款\nRetrieved sources:\n- 退款政策：7 天内可退", "怎么申请退款")).text();

        assertThat(answer).contains("退款问题需先确认");
    }

    @Test
    void fallsBackToPromptParsingWhenStructuredMessageMissing() {
        // 没有结构化 userMessage 时，仍要能从提示词里解析出当前问题（避免老调用点失效）
        String answer = adapter.complete(knowledgeRequest(
                "User question: 怎么申请退款\nRetrieved sources:\n- 退款政策：7 天内可退", null)).text();

        assertThat(answer).contains("退款问题需先确认");
    }
}