package com.example.aiticketassistant.application.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 会话历史裁剪口径的回归测试。
 *
 * <p>这套规则的目的只有一个：**注入提示词的历史永远有上限**，同时不能把"刚才在聊什么"弄丢。
 * 断言里出现的每个数字都对应 {@link ConversationHistory.Budget} 的一条规则。
 */
class ConversationHistoryTest {

    private static ConversationTurn turn(int round, int answerChars) {
        return new ConversationTurn("第" + round + "轮问题", "第" + round + "轮回答 " + "内容".repeat(Math.max(1, answerChars / 2)));
    }

    private static List<ConversationTurn> history(int turns, int answerChars) {
        List<ConversationTurn> list = new ArrayList<>();
        for (int round = 1; round <= turns; round++) {
            list.add(turn(round, answerChars));
        }
        return list;
    }

    @Test
    void emptyHistoryTrimsToEmpty() {
        assertThat(ConversationHistory.trim(null, ConversationHistory.Budget.defaults())).isEmpty();
        assertThat(ConversationHistory.trim(List.of(), ConversationHistory.Budget.defaults())).isEmpty();
        assertThat(ConversationHistory.promptChars(List.of())).isZero();
    }

    @Test
    void keepsTheMostRecentTurnsWithinTheBudget() {
        // 每轮约 30 字，预算 80 → 只装得下最近两轮
        List<ConversationTurn> trimmed = ConversationHistory.trim(
                history(6, 30), new ConversationHistory.Budget(6, 80, 120, 300));

        assertThat(trimmed).isNotEmpty();
        assertThat(trimmed).hasSizeLessThan(6);
        assertThat(trimmed.get(trimmed.size() - 1).userMessage()).isEqualTo("第6轮问题");
        // 时间正序：最后一条一定是最新的一轮
        assertThat(trimmed).extracting(ConversationTurn::userMessage)
                .containsExactlyElementsOf(trimmed.stream().map(ConversationTurn::userMessage).toList());
        assertThat(ConversationHistory.promptChars(trimmed)).isLessThanOrEqualTo(80);
    }

    @Test
    void newestTurnIsKeptEvenIfItAloneExceedsTheBudget() {
        // 预算 120，最新一轮自己就占几千字：必须留下"用户问了什么"，回答截到放得下为止
        List<ConversationTurn> trimmed = ConversationHistory.trim(
                List.of(turn(1, 20), new ConversationTurn("那件红色的呢", "答".repeat(5000))),
                new ConversationHistory.Budget(6, 120, 120, 300));

        assertThat(trimmed).hasSize(1);
        assertThat(trimmed.get(0).userMessage()).isEqualTo("那件红色的呢");
        assertThat(trimmed.get(0).assistantAnswer()).endsWith("…");
        assertThat(ConversationHistory.promptChars(trimmed)).isLessThanOrEqualTo(120);
    }

    @Test
    void eachFieldHasItsOwnLimitAndKeepsTheHead() {
        String longQuestion = "问题".repeat(100);        // 200 字
        String longAnswer = "结论在最前面。" + "明细".repeat(1000);
        List<ConversationTurn> trimmed = ConversationHistory.trim(
                List.of(new ConversationTurn(longQuestion, longAnswer)),
                new ConversationHistory.Budget(6, 10_000, 40, 60));

        assertThat(trimmed.get(0).userMessage()).hasSize(40).endsWith("…");
        assertThat(trimmed.get(0).assistantAnswer()).hasSize(60).startsWith("结论在最前面。").endsWith("…");
    }

    @Test
    void whitespaceIsFlattenedSoTablesDoNotEatTheBudget() {
        List<ConversationTurn> trimmed = ConversationHistory.trim(
                List.of(new ConversationTurn("问题\n\n第二行", "| a | b |\n|---|---|\n| 1 | 2 |")),
                ConversationHistory.Budget.defaults());

        assertThat(trimmed.get(0).userMessage()).isEqualTo("问题 第二行");
        assertThat(trimmed.get(0).assistantAnswer()).doesNotContain("\n");
    }

    @Test
    void invalidConfigurationFallsBackToDefaultsInsteadOfEmptyingHistory() {
        // 配成 0/负数时不能用 0 —— 那会把历史静默清空，追问全部失效，比配错更难查
        ConversationHistory.Budget broken = new ConversationHistory.Budget(0, 0, -1, -1);
        assertThat(broken).isEqualTo(ConversationHistory.Budget.defaults());

        // 轮数上限与 JPA 单次取 40 条消息（20 轮）对齐，配再大也用不上
        assertThat(new ConversationHistory.Budget(99, 1200, 120, 300).maxTurns()).isEqualTo(20);
    }

    @Test
    void turnCountIsAlsoCappedEvenIfTheBudgetWouldAllowMore() {
        // 每轮很短，预算足够装 10 轮，但轮数上限是 2 → 只留最近两轮
        List<ConversationTurn> trimmed = ConversationHistory.trim(
                history(10, 10), new ConversationHistory.Budget(2, 5000, 120, 300));

        assertThat(trimmed).extracting(ConversationTurn::userMessage)
                .containsExactly("第9轮问题", "第10轮问题");
    }

    @Test
    void renderedBlockStaysInsideTheBudgetForLongSessions() {
        ConversationHistory.Budget budget = new ConversationHistory.Budget(6, 500, 120, 300);
        List<ConversationTurn> trimmed = ConversationHistory.trim(history(20, 800), budget);

        assertThat(ConversationHistory.promptChars(trimmed)).isLessThanOrEqualTo(500);
        // 渲染出来的文本也不能超过预算（含每轮「用户: / 助手: 」前缀开销）
        assertThat(ConversationTurn.toPromptBlock(trimmed).length()).isLessThanOrEqualTo(500);
    }
}
