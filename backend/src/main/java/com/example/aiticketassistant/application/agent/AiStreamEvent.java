package com.example.aiticketassistant.application.agent;

/**
 * AI 流式输出的单个事件。
 *
 * <p>目前只有一种类型 {@link #DELTA}（正文增量）。用记录而不是裸字符串，
 * 是为了以后能加"思考过程 / 工具调用增量"这类分片而不破坏调用方。
 *
 * <p>约定：{@code delta} 是**要拼进最终回答的原文**（不丢空格、不做格式化），
 * 调用方按到达顺序累加即可还原全文；流结束由 Flux 的完成信号表达，不需要额外的 done 事件。
 */
public record AiStreamEvent(String type, String delta) {
    public static final String DELTA = "delta";

    public static AiStreamEvent delta(String text) {
        return new AiStreamEvent(DELTA, text == null ? "" : text);
    }

    public boolean isEmpty() {
        return delta == null || delta.isEmpty();
    }
}
