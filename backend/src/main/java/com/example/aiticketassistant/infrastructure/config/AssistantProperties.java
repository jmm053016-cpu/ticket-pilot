package com.example.aiticketassistant.infrastructure.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "assistant")
public record AssistantProperties(Ai ai, Retrieval retrieval, Prompts prompts, Cors cors, Conversation conversation) {
    /**
     * AI 适配器配置。
     *
     * <p>支持的开源/国内 provider：`deepseek`（默认推荐）、`qwen`、`fallback`（规则兜底）。
     * 2026-11-04 起移除 Anthropic（项目不再使用该厂商模型），对应的 key / model / max-tokens /
     * enable-real-client 配置项一并删除；`timeout` 保留，因为 DeepSeek 与 Qwen 都用它控制单次调用超时。
     */
    public record Ai(
            String provider,
            String qwenApiKey,
            String qwenModel,
            long qwenMaxTokens,
            String deepSeekApiKey,
            String deepSeekModel,
            String deepSeekBaseUrl,
            long deepSeekMaxTokens,
            Duration timeout) {
        public boolean hasQwenApiKey() {
            return qwenApiKey != null && !qwenApiKey.isBlank();
        }

        public boolean hasDeepSeekApiKey() {
            return deepSeekApiKey != null && !deepSeekApiKey.isBlank();
        }
    }

    /**
     * 检索配置（RAG 的向量那一路）。
     *
     * <p>{@code embeddingProvider} 两种取值：
     * <ul>
     *   <li>{@code hash}（默认）：本地 TF-IDF 特征哈希，离线、零成本、确定性，但**不懂语义**；</li>
     *   <li>{@code openai-compatible}：任何 OpenAI 兼容的 {@code /embeddings} 服务（DashScope/Qwen、
     *       SiliconFlow、Ollama、vLLM…），真语义向量。换服务商只改 base-url / model / api-key。</li>
     * </ul>
     * 见 {@code PROJECT_OVERVIEW.md} §6.5 ⑤。
     */
    public record Retrieval(
            String embeddingProvider,
            String embeddingBaseUrl,
            String embeddingModel,
            String embeddingApiKey,
            long embeddingDimensions) {}

    /**
     * 提示词加载配置（热加载，见 {@code ClasspathPromptCatalog} 与 §5.4.4）。
     *
     * @param dir           外部目录（可选，优先于 classpath）；本地开发指向 {@code backend/src/main/resources/prompts}
     *                      就能改完即生效，不用重启
     * @param hotReload     是否按文件变化自动重载（默认 true）；jar 内运行时自动退化为启动加载一次
     * @param checkInterval 懒检查的节流间隔（默认 1s），越小越灵敏、越费 stat
     */
    public record Prompts(String dir, boolean hotReload, Duration checkInterval) {}

    public record Cors(List<String> allowedOrigins) {}    /**
     * 会话历史注入提示词的上限（成本控制，见 {@code ConversationHistory}）。
     *
     * <p>四个值都允许缺省：缺省或配错时由 {@code ConversationHistory.Budget} 回落到默认值，
     * 不会因为少写一行 yml 就把历史清空。
     */
    public record Conversation(int maxTurns, int maxPromptChars, int maxUserChars, int maxAnswerChars) {}
}
