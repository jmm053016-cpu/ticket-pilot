package com.example.aiticketassistant.application.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link AiClientPort#stream} 默认实现的回归测试。
 *
 * <p>为什么要锁：不是所有适配器都能真流式（规则兜底 / Qwen），它们靠这个默认实现
 * 「整段切句 + 90ms 一句」保持与改造前一致的用户观感；同时它必须**惰性执行**，
 * 否则阻塞式 complete() 会跑到 Netty 事件循环线程上。
 */
class AiClientPortTest {

    private static AiRequest request() {
        return new AiRequest("DirectQwen", "system", "user", java.util.Map.of(), false);
    }

    @Test
    void defaultStreamSplitsIntoSentencesAndKeepsTextIntact() {
        AiClientPort port = ignored -> AiResponse.fallback("第一句。第二句。\n第三行");

        List<String> deltas = port.stream(request())
                .map(AiStreamEvent::delta)
                .collectList()
                .block();

        assertThat(deltas).containsExactly("第一句。", "第二句。", "\n", "第三行");
        assertThat(String.join("", deltas)).isEqualTo("第一句。第二句。\n第三行");
        assertThat(port.stream(request()).blockFirst()).isNotNull();
    }

    @Test
    void defaultStreamIsLazySoBlockingAdaptersRunOnTheSubscriberThread() {
        // complete() 若在调用 stream() 时立刻执行，阻塞式适配器就会在事件循环上卡住
        AtomicInteger calls = new AtomicInteger();
        AiClientPort port = ignored -> {
            calls.incrementAndGet();
            return AiResponse.fallback("只有一句。");
        };

        Mono<Void> notSubscribed = port.stream(request()).then();
        assertThat(calls).as("未订阅时不应该调用模型").hasValue(0);

        notSubscribed.block();
        assertThat(calls).hasValue(1);
    }

    @Test
    void emptyAnswerProducesEmptyStream() {
        AiClientPort port = ignored -> AiResponse.fallback("");

        assertThat(port.stream(request()).collectList().block()).isEmpty();
    }
}
