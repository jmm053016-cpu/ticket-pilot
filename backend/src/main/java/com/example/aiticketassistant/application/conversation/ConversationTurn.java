package com.example.aiticketassistant.application.conversation;

/**
 * 一轮完整的对话（用户提问 + 助手回答）。
 *
 * <p>只有"问与答都成功产出"的轮次才会被记录，因此不存在只有半个轮次的历史。
 */
public record ConversationTurn(String userMessage, String assistantAnswer) {

    /**
     * 渲染成注入提示词的纯文本；没有历史时返回空串（模板里对应位置就是空白）。
     *
     * <p>调用方拿到的历史**一定已经按预算裁剪过**（见 {@link ConversationHistory#trim}），
     * 所以这里不再做长度控制，只管拼格式。回答为空时只输出「用户: …」一行 ——
     * 预算是硬上限，最新一轮挤压时可能只剩得下问题。
     */
    public static String toPromptBlock(java.util.List<ConversationTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : turns) {
            builder.append("用户: ").append(turn.userMessage()).append('\n');
            if (turn.assistantAnswer() != null && !turn.assistantAnswer().isBlank()) {
                builder.append("助手: ").append(turn.assistantAnswer()).append('\n');
            }
        }
        return builder.toString().trim();
    }
}
