package com.example.aiticketassistant.application.prompt;

import java.util.List;

/**
 * 提示词 ID 常量。每个 ID 对应 {@code classpath:prompts/<id>.system.md}
 * 与 {@code classpath:prompts/<id>.user.md} 两个文件。
 */
public final class PromptIds {
    public static final String INTENT_AGENT = "intent-agent";
    public static final String ORDER_AGENT = "order-agent";
    public static final String KNOWLEDGE_AGENT = "knowledge-agent";
    public static final String DIRECT_ANSWER = "direct-answer";

    /** 启动期必须全部存在的提示词，缺失即启动失败。 */
    public static final List<String> ALL = List.of(INTENT_AGENT, ORDER_AGENT, KNOWLEDGE_AGENT, DIRECT_ANSWER);

    private PromptIds() {}
}
