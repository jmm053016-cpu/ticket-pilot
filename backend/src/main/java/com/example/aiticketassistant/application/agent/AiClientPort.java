package com.example.aiticketassistant.application.agent;

import java.time.Duration;
import reactor.core.publisher.Flux;

/**
 * 调用大模型的端口。
 *
 * <p>两个能力：
 * <ul>
 *   <li>{@link #complete}：一次性拿整段结果（意图识别这类**要解析 JSON** 的调用必须用它，
 *       半截 JSON 没法解析）；</li>
 *   <li>{@link #stream}：逐段拿正文增量（最终回答用它，前端才能真正"逐字出现"）。</li>
 * </ul>
 *
 * <p>{@code complete} 是唯一的抽象方法，所以实现类/测试里的 lambda 都只写它；
 * {@code stream} 有默认实现：把整段结果按句切分后逐句吐出（90ms 一句）。
 * 这样**不支持真流式的适配器行为不变**（观感与改造前完全一致），只有真正实现了 SSE 的适配器
 * （DeepSeek）才会覆盖它，做到 token 级推送。
 */
public interface AiClientPort {
    AiResponse complete(AiRequest request);

    /**
     * 真流式生成（正文增量）。
     *
     * <p>默认实现 = 「伪流式」：等 {@link #complete} 返回后按句切分、每句间隔 90ms。
     * **必须用 {@code Flux.defer} 包住**：{@code complete} 在 Qwen 上是阻塞 HTTP，
     * 若在调用 {@code stream()} 的那一刻就执行，就会在 Netty 事件循环线程上阻塞；
     * 包成 defer 后它只在订阅时执行，配合调用方的 {@code subscribeOn(boundedElastic)} 才安全。
     *
     * @return 增量事件流；错误直接以 Flux 错误向下传播（由工作流统一转成 SSE error 事件）
     */
    default Flux<AiStreamEvent> stream(AiRequest request) {
        return Flux.defer(() -> {
            AiResponse response = complete(request);
            return Flux.fromIterable(StreamChunks.sentences(response.text()))
                    .delayElements(Duration.ofMillis(90))
                    .map(AiStreamEvent::delta);
        });
    }
}
