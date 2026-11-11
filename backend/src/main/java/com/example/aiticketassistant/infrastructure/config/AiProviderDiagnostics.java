package com.example.aiticketassistant.infrastructure.config;

import com.example.aiticketassistant.application.agent.AiClientPort;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动自检：把「到底哪个 LLM 适配器生效、key 有没有读到」打进日志。
 *
 * <p>存在的理由：`provider` 由环境变量 / `.env` / 默认值三方博弈决定，配错了**不会报错**——
 * 只是静默退回规则兜底，回答看起来"像那么回事"。本地用 IDEA 启动时尤其容易踩（Spring 不读 `.env`，
 * 工作目录不同也会影响相对路径），所以这里在启动完成后把结论明明白白打出来。
 */
@Component
public class AiProviderDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(AiProviderDiagnostics.class);
    /** 相对当前工作目录依次尝试，命中第一个即认为是本次生效的 .env。 */
    private static final String[] ENV_FILE_CANDIDATES = {".env", "../.env", "../../.env"};

    private final AiClientPort aiClient;
    private final AssistantProperties properties;

    public AiProviderDiagnostics(AiClientPort aiClient, AssistantProperties properties) {
        this.aiClient = aiClient;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportActiveProvider() {
        String adapter = aiClient.getClass().getSimpleName();
        String provider = properties.ai() == null ? null : properties.ai().provider();
        boolean deepSeekKey = properties.ai() != null && properties.ai().hasDeepSeekApiKey();
        String envFile = findEnvFile();

        log.info("[AI] 生效适配器 = {}，配置 provider = {}，DeepSeek key = {}",
                adapter, provider, deepSeekKey ? "已配置(长度 " + properties.ai().deepSeekApiKey().length() + ")" : "缺失");
        log.info("[AI] 工作目录 = {}，命中的 .env = {}", Path.of("").toAbsolutePath(), envFile);

        if ("FallbackAiClientAdapter".equals(adapter)) {
            log.warn("[AI] 当前是规则兜底，**不会调用大模型**。排查顺序："
                    + "① .env 里是否设了 AI_PROVIDER=deepseek 与 DEEPSEEK_API_KEY；"
                    + "② application.yml 的 spring.config.import 是否指向了实际存在的 .env（对照上一行日志里命中的路径）；"
                    + "③ 改完是否重启（IDEA 需重新 Build，让 target/classes 里的 application.yml 更新）。");
        } else {
            log.info("[AI] 已接入真实大模型，回答耗时会明显长于规则兜底（每次问答至少 2 次调用）。");
        }
    }

    private String findEnvFile() {
        for (String candidate : ENV_FILE_CANDIDATES) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().toString();
            }
        }
        return "未找到（只在 AI_PROVIDER 等已由环境变量提供时才会正常工作）";
    }
}
