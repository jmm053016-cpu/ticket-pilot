package com.example.aiticketassistant.application.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 注入提示词的会话历史「裁剪口径」（纯函数 + 一个配置载体）。
 *
 * <p>为什么要裁剪：历史是按「用户: … / 助手: …」整段拼进 4 个提示词的，而助手的回答经常是
 * 几百上千字（价格表、明细表）。固定回看 6 轮 = 每轮至少 2 次模型调用 × 全量历史，
 * 长会话下 prompt 会被历史撑爆：**又慢又贵**，而且模型盯着旧内容反而容易答偏。
 *
 * <p>三条规则（顺序即优先级）：
 * <ol>
 *   <li><b>单条截断</b>：用户消息、助手回答各有一个字数上限，超出保留开头（结论优先的格式下开头最重要）+「…」；</li>
 *   <li><b>总量预算</b>：整块历史有字数上限，**从最新一轮往前装**，装满即停 —— 越近的对话越可能被追问；</li>
 *   <li><b>最新一轮必留</b>：哪怕最新一轮自己就超预算，也要先把"用户刚才问的是什么"放进预算内，
 *       剩下的额度再给回答（否则追问「那不加呢？」会完全接不上）。</li>
 * </ol>
 *
 * <p>裁剪发生在 {@code AssistantWorkflowService.loadHistory}，**它之后的所有调用点都拿不到超预算的历史**，
 * 所以不需要每个 Agent 各写一遍（以前各调用点直接渲染全量历史，谁都不知道总长度）。
 */
public final class ConversationHistory {
    /** 每轮的渲染开销：「用户: 」「助手: 」前缀 + 两个换行。 */
    private static final int TURN_OVERHEAD = 10;
    /** {@code JpaConversationMemory} 单次最多取 40 条消息（20 轮），这里给同一个上限，免得配了也用不上。 */
    static final int MAX_TURNS_LIMIT = 20;

    /**
     * 历史注入的预算。
     *
     * @param maxTurns        回看多少轮（从库里读多少）
     * @param maxPromptChars  整块历史的字符预算（中文 1 字 ≈ 1 token 量级，够用来控成本）
     * @param maxUserChars    单条用户消息上限
     * @param maxAnswerChars  单条助手回答上限
     */
    public record Budget(int maxTurns, int maxPromptChars, int maxUserChars, int maxAnswerChars) {
        /** 默认值：6 轮 / 1200 字总量 / 用户 120 字 / 回答 300 字。 */
        public static Budget defaults() {
            return new Budget(6, 1200, 120, 300);
        }

        /**
         * 非法或缺失的配置一律回落到默认值。
         *
         * <p>为什么不是抛异常：这几个数只影响"历史注入多少"，配错不该让整个服务起不来；
         * 但必须**回落**而不是用 0 —— 0 会让历史被静默清空，追问全部失效，比配错更难查。
         */
        public Budget {
            maxTurns = clamp(maxTurns, 6, MAX_TURNS_LIMIT);
            maxPromptChars = positiveOr(maxPromptChars, 1200);
            maxUserChars = positiveOr(maxUserChars, 120);
            maxAnswerChars = positiveOr(maxAnswerChars, 300);
        }

        private static int clamp(int value, int fallback, int max) {
            return Math.min(max, positiveOr(value, fallback));
        }

        private static int positiveOr(int value, int fallback) {
            return value > 0 ? value : fallback;
        }
    }

    private ConversationHistory() {}

    /**
     * 按预算裁剪历史（保持时间正序返回）。
     *
     * @param turns  时间正序的历史（最新的在最后），可为 null
     * @param budget 预算；null 用默认预算
     * @return 裁剪后的历史；无历史返回空列表
     */
    public static List<ConversationTurn> trim(List<ConversationTurn> turns, Budget budget) {
        if (turns == null || turns.isEmpty()) {
            return List.of();
        }
        Budget limits = budget == null ? Budget.defaults() : budget;
        List<ConversationTurn> kept = new ArrayList<>();
        int remaining = limits.maxPromptChars();
        for (int index = turns.size() - 1; index >= 0; index--) {
            ConversationTurn turn = turns.get(index);
            String user = shorten(turn.userMessage(), limits.maxUserChars());
            String answer = shorten(turn.assistantAnswer(), limits.maxAnswerChars());
            int cost = user.length() + answer.length() + TURN_OVERHEAD;
            if (cost <= remaining) {
                kept.add(new ConversationTurn(user, answer));
                remaining -= cost;
                // 轮数也是硬上限：调用方通常已经按 maxTurns 取过，这里再兜一次
                if (kept.size() >= limits.maxTurns()) {
                    break;
                }
                continue;
            }
            if (kept.isEmpty()) {
                // 最新一轮必留：先把“用户问了什么”塞进预算，剩下的额度给回答（放不下就留空）
                String userOnly = shorten(turn.userMessage(), Math.min(limits.maxUserChars(), Math.max(1, remaining - TURN_OVERHEAD)));
                int leftForAnswer = remaining - userOnly.length() - TURN_OVERHEAD;
                String answerOnly = leftForAnswer > 0
                        ? shorten(turn.assistantAnswer(), Math.min(limits.maxAnswerChars(), leftForAnswer))
                        : "";
                kept.add(new ConversationTurn(userOnly, answerOnly));
            }
            break;
        }
        Collections.reverse(kept);
        return List.copyOf(kept);
    }

    /** 渲染后的字符数（用于日志和断言：成本控制的效果要能看见）。 */
    public static int promptChars(List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ConversationTurn turn : turns) {
            total += length(turn.userMessage()) + length(turn.assistantAnswer()) + TURN_OVERHEAD;
        }
        return total;
    }

    /**
     * 截断到 {@code max} 字以内：保留开头 + 「…」。
     *
     * <p>为什么保留开头：回答的格式是"结论优先、明细在后"，历史里真正有用的是结论；
     * 把表格尾巴留下来反而挤掉别的轮次。同时把连续空白压成一个空格（表格换行在历史里没有意义）。
     */
    static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        if (max <= 0) {
            return "";
        }
        return flat.length() <= max ? flat : flat.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
