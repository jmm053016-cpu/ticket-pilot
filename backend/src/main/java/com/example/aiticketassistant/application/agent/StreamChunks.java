package com.example.aiticketassistant.application.agent;

import java.util.Arrays;
import java.util.List;

/**
 * 把一段完整文本切成"打字机"分片（按句号/换行）。
 *
 * <p>两种用途，**同一口径**：
 * <ul>
 *   <li>不支持真流式的适配器（规则兜底 / Qwen）在 {@link AiClientPort#stream} 的默认实现里，
 *       用它把整段回答切句后逐句推送，观感与改造前一致；</li>
 *   <li>真流式适配器（DeepSeek）的 token 流结束后，确定性文案（订单情况/需要你确认/工单/失败提示）
 *       也按同样粒度追加，避免"最后一大段瞬间砸到屏幕上"。</li>
 * </ul>
 */
public final class StreamChunks {

    private StreamChunks() {}

    /** 按「。」与换行切分，丢弃空片；返回顺序即原文顺序。 */
    public static List<String> sentences(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(text.split("(?<=。|\n)"))
                .filter(chunk -> !chunk.isEmpty())
                .toList();
    }
}
