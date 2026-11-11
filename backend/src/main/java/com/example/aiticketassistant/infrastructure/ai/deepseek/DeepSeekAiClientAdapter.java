package com.example.aiticketassistant.infrastructure.ai.deepseek;

import com.example.aiticketassistant.application.agent.AiClientPort;
import com.example.aiticketassistant.application.agent.AiRequest;
import com.example.aiticketassistant.application.agent.AiResponse;
import com.example.aiticketassistant.application.agent.AiStreamEvent;
import com.example.aiticketassistant.infrastructure.config.AssistantProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@Component
@ConditionalOnProperty(prefix = "assistant.ai", name = "provider", havingValue = "deepseek")
public class DeepSeekAiClientAdapter implements AiClientPort {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekAiClientAdapter.class);
    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions";
    /** OpenAI 兼容协议的流结束标记（作为 SSE data 收到的是字面量 [DONE]，不是 JSON）。 */
    private static final String SSE_DONE = "[DONE]";

    private final AssistantProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public DeepSeekAiClientAdapter(AssistantProperties properties, WebClient.Builder builder, ObjectMapper objectMapper) {
        this.properties = properties;
        this.webClient = builder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public AiResponse complete(AiRequest request) {
        String apiKey = apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            if (request.expectJson()) {
                return AiResponse.fallback(jsonFallback());
            }
            return AiResponse.fallback("DeepSeek API Key 未配置，请在项目根目录 .env 填写 DEEPSEEK_API_KEY 后重启 backend。");
        }
        try {
            DeepSeekChatResponse response = webClient.post()
                    .uri(endpoint())
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody(request, false))
                    .retrieve()
                    .bodyToMono(DeepSeekChatResponse.class)
                    .block(timeout());
            String text = response == null || response.choices() == null || response.choices().isEmpty()
                    ? ""
                    : response.choices().get(0).message().content();
            return new AiResponse(text, Map.of("model", properties.ai().deepSeekModel(), "provider", "deepseek"), false);
        } catch (Exception ex) {
            log.warn("DeepSeek API call failed for agent {}: {}", request.agentName(), ex.getMessage());
            return AiResponse.fallback(fallbackFor(request));
        }
    }

    /**
     * 真流式：{@code stream=true} + 逐条解析上游 SSE 的 {@code choices[0].delta.content}。
     *
     * <p>为什么用 {@code bodyToFlux(String.class)} 而不是把分片直接映射成 DTO：
     * OpenAI 兼容协议的流最后一条是字面量 {@code data: [DONE]}（不是 JSON），
     * 直接反序列化会在结尾抛解析错误。这里先拿字符串，再自己判断/解析。
     *
     * <p>失败处理对齐 {@link #complete}：**一个 token 都还没吐**时降级成本地兜底文案（用户不会看到空白回答）；
     * 已经吐过一部分就只记日志结束，避免"答案一半 + 一句道歉"的拼接。
     */
    @Override
    public Flux<AiStreamEvent> stream(AiRequest request) {
        String apiKey = apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("DeepSeek API Key 未配置，流式回答降级为本地文案");
            return Flux.just(AiStreamEvent.delta(fallbackFor(request)));
        }
        AtomicBoolean emitted = new AtomicBoolean(false);
        return webClient.post()
                .uri(endpoint())
                .header("Authorization", "Bearer " + apiKey)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(request, true))
                .retrieve()
                .bodyToFlux(String.class)
                .takeUntil(SSE_DONE::equals)
                // 注意不能用 map + filter：Reactor 的 map 返回 null 会直接报错
                // （"The mapper returned a null value"），而这里大多数分片本来就该被丢掉。
                .<AiStreamEvent>handle((payload, sink) -> {
                    AiStreamEvent event = toStreamEvent(payload);
                    if (event != null) {
                        sink.next(event);
                    }
                })
                .doOnNext(event -> emitted.set(true))
                // 相邻两条分片之间超过 timeout 视为上游卡死
                .timeout(timeout())
                .onErrorResume(ex -> {
                    log.warn("DeepSeek 流式调用失败 agent={}：{}", request.agentName(), ex.getMessage());
                    return emitted.get() ? Flux.empty() : Flux.just(AiStreamEvent.delta(fallbackFor(request)));
                });
    }

    /** 把一条上游 SSE data 解析成增量事件；非内容分片（心跳 / [DONE] / 空 delta）返回 null。 */
    AiStreamEvent toStreamEvent(String payload) {
        if (payload == null || payload.isBlank() || SSE_DONE.equals(payload.trim())) {
            return null;
        }
        try {
            JsonNode delta = objectMapper.readTree(payload).path("choices").path(0).path("delta").path("content");
            String text = delta.isMissingNode() || delta.isNull() ? "" : delta.asText("");
            return text.isEmpty() ? null : AiStreamEvent.delta(text);
        } catch (Exception ex) {
            log.debug("跳过无法解析的流式分片: {}", payload, ex);
            return null;
        }
    }

    /** 请求体；只有 {@code stream=true} 时才开流（意图识别等要解析 JSON 的调用永远是 false）。 */
    private Map<String, Object> requestBody(AiRequest request, boolean stream) {
        return Map.of(
                "model", properties.ai().deepSeekModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", request.systemPrompt()),
                        Map.of("role", "user", "content", request.userPrompt())),
                "temperature", request.expectJson() ? 0.1 : 0.3,
                "max_tokens", properties.ai().deepSeekMaxTokens(),
                "stream", stream);
    }

    private String apiKey() {
        return properties.ai().hasDeepSeekApiKey()
                ? properties.ai().deepSeekApiKey()
                : System.getenv("DEEPSEEK_API_KEY");
    }

    private String endpoint() {
        String base = properties.ai().deepSeekBaseUrl();
        if (base == null || base.isBlank()) {
            return DEFAULT_ENDPOINT;
        }
        base = base.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    private Duration timeout() {
        Duration configured = properties.ai().timeout();
        return configured == null ? Duration.ofSeconds(30) : configured;
    }

    private String fallbackFor(AiRequest request) {
        if (request.expectJson()) {
            return jsonFallback();
        }
        return "DeepSeek API 当前不可用，系统已降级到本地规则链路。";
    }

    private String jsonFallback() {
        return "{\"intent\":\"GENERAL_SUPPORT\",\"title\":\"智能客服咨询\",\"priority\":\"MEDIUM\",\"confidence\":0.3,\"tool_calls\":[]}";
    }

    public record DeepSeekChatResponse(List<Choice> choices) {}
    public record Choice(Message message) {}
    public record Message(String content) {}
}
