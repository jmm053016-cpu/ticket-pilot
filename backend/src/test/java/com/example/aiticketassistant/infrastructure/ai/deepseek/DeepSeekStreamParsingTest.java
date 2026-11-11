package com.example.aiticketassistant.infrastructure.ai.deepseek;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.application.agent.AiStreamEvent;
import com.example.aiticketassistant.infrastructure.config.AssistantProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * DeepSeek 流式分片解析（不联网）。
 *
 * <p>要锁住的是「哪些分片该被丢掉」：OpenAI 兼容协议里除了正文 delta，还会有
 * role-only 首帧、{@code finish_reason} 尾帧、心跳、以及最后的字面量 {@code [DONE]} ——
 * 任何一条被误当成正文，用户就会在回答里看到 "null" 或 JSON 碎片。
 */
class DeepSeekStreamParsingTest {

    private static DeepSeekAiClientAdapter adapter() {
        // provider, qwenApiKey, qwenModel, qwenMaxTokens, deepSeekApiKey, deepSeekModel, deepSeekBaseUrl, deepSeekMaxTokens, timeout
        AssistantProperties.Ai ai = new AssistantProperties.Ai(
                "deepseek", null, null, 0L,
                "test-key", "deepseek-chat", null, 2000L, Duration.ofSeconds(5));
        return new DeepSeekAiClientAdapter(
                new AssistantProperties(ai, null, null, null, null), WebClient.builder(), new ObjectMapper());
    }

    @Test
    void contentDeltaBecomesAnIncrement() {
        String chunk = """
                {"id":"1","choices":[{"index":0,"delta":{"content":"羊毛混纺"},"finish_reason":null}]}
                """;

        assertThat(adapter().toStreamEvent(chunk))
                .isEqualTo(AiStreamEvent.delta("羊毛混纺"));
    }

    @Test
    void terminalAndNonContentFramesAreDropped() {
        DeepSeekAiClientAdapter adapter = adapter();

        assertThat(adapter.toStreamEvent("[DONE]")).as("协议结束标记").isNull();
        assertThat(adapter.toStreamEvent("  ")).as("心跳/空行").isNull();
        assertThat(adapter.toStreamEvent("""
                {"choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}
                """)).as("role-only 首帧").isNull();
        assertThat(adapter.toStreamEvent("""
                {"choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}
                """)).as("空内容 / 结束帧").isNull();
    }

    @Test
    void malformedFrameIsSkippedInsteadOfThrowing() {
        // 上游偶尔会插入非 JSON 的注释行；解析失败只能跳过，不能让整条流崩掉
        assertThat(adapter().toStreamEvent("data: oops")).isNull();
        assertThat(adapter().toStreamEvent("{\"choices\":[]}")).isNull();
    }
}
