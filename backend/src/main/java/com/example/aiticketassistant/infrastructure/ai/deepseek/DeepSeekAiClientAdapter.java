package com.example.aiticketassistant.infrastructure.ai.deepseek;

import com.example.aiticketassistant.application.agent.AiClientPort;
import com.example.aiticketassistant.application.agent.AiRequest;
import com.example.aiticketassistant.application.agent.AiResponse;
import com.example.aiticketassistant.infrastructure.config.AssistantProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
@ConditionalOnProperty(prefix = "assistant.ai", name = "provider", havingValue = "deepseek")
public class DeepSeekAiClientAdapter implements AiClientPort {
    private static final Logger log = LoggerFactory.getLogger(DeepSeekAiClientAdapter.class);
    private static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions";

    private final AssistantProperties properties;
    private final WebClient webClient;

    public DeepSeekAiClientAdapter(AssistantProperties properties, WebClient.Builder builder) {
        this.properties = properties;
        this.webClient = builder.build();
    }

    @Override
    public AiResponse complete(AiRequest request) {
        String apiKey = properties.ai().hasDeepSeekApiKey()
                ? properties.ai().deepSeekApiKey()
                : System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            if (request.expectJson()) {
                return AiResponse.fallback(jsonFallback());
            }
            return AiResponse.fallback("DeepSeek API Key 未配置，请在项目根目录 .env 填写 DEEPSEEK_API_KEY 后重启 backend。");
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", properties.ai().deepSeekModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", request.systemPrompt()),
                            Map.of("role", "user", "content", request.userPrompt())),
                    "temperature", request.expectJson() ? 0.1 : 0.3,
                    "max_tokens", properties.ai().deepSeekMaxTokens(),
                    "stream", false);
            DeepSeekChatResponse response = webClient.post()
                    .uri(endpoint())
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
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
