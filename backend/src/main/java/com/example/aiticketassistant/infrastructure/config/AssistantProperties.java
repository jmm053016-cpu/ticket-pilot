package com.example.aiticketassistant.infrastructure.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "assistant")
public record AssistantProperties(Ai ai, Chroma chroma, Cors cors) {
    public record Ai(
            String provider,
            String anthropicApiKey,
            String qwenApiKey,
            String model,
            String qwenModel,
            long maxTokens,
            long qwenMaxTokens,
            String deepSeekApiKey,
            String deepSeekModel,
            String deepSeekBaseUrl,
            long deepSeekMaxTokens,
            Duration timeout,
            boolean enableRealClient) {
        public boolean hasApiKey() {
            return anthropicApiKey != null && !anthropicApiKey.isBlank();
        }

        public boolean hasQwenApiKey() {
            return qwenApiKey != null && !qwenApiKey.isBlank();
        }

        public boolean hasDeepSeekApiKey() {
            return deepSeekApiKey != null && !deepSeekApiKey.isBlank();
        }
    }

    public record Chroma(String baseUrl, String collection) {}

    public record Cors(List<String> allowedOrigins) {}
}
