package com.example.aiticketassistant.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.aiticketassistant.application.prompt.PromptCatalog;
import com.example.aiticketassistant.application.prompt.PromptIds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 「提示词热加载」在**真容器里**的装配验证。
 *
 * <p>为什么需要它：单元测试（{@code PromptHotReloadTest}）证明的是"给了外部目录就能热加载"，
 * 但 Spring 那一半曾经是坏的 —— 本类有两个构造函数，Spring 在都没标注时**回落到无参构造函数**，
 * 于是 {@code assistant.prompts.dir} 配了也白配（真机设了 {@code PROMPTS_DIR} 却仍读 {@code target/classes}）。
 * 这里断言"配了外部目录 → 容器里读的就是外部目录"，把这个坑钉死。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "assistant.ai.provider=fallback",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
        })
@ActiveProfiles("test")
class PromptHotReloadWiringTest {
    private static final Path PROMPTS_DIR = createExternalPromptsDir();

    @DynamicPropertySource
    static void promptsProperties(DynamicPropertyRegistry registry) {
        registry.add("assistant.prompts.dir", () -> PROMPTS_DIR.toString());
        registry.add("assistant.prompts.check-interval", () -> "PT0.05S");
    }

    private static Path createExternalPromptsDir() {
        try {
            Path dir = Files.createTempDirectory("prompts-wiring");
            for (String id : PromptIds.ALL) {
                Files.writeString(dir.resolve(id + ".system.md"), "external system " + id, StandardCharsets.UTF_8);
                Files.writeString(dir.resolve(id + ".user.md"), "external user " + id + " {{userMessage}}", StandardCharsets.UTF_8);
            }
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException ex) {
            throw new IllegalStateException("准备外部提示词目录失败", ex);
        }
    }

    @Autowired
    private PromptCatalog promptCatalog;

    @Test
    void configuredExternalDirectoryIsWhatActuallyGetsRead() {
        assertThat(promptCatalog.get(PromptIds.DIRECT_ANSWER).system())
                .as("容器必须读到 assistant.prompts.dir 指向的文件，而不是 classpath 副本")
                .isEqualTo("external system " + PromptIds.DIRECT_ANSWER);

        assertThat(promptCatalog.info()).allSatisfy(info -> {
            assertThat(info.systemFile()).contains(PROMPTS_DIR.toString());
            assertThat(info.hotReloadable()).isTrue();
        });
    }

    @Test
    void editingAFileTakesEffectWithoutRestart() throws IOException, InterruptedException {
        Files.writeString(PROMPTS_DIR.resolve(PromptIds.INTENT_AGENT + ".system.md"),
                "external system edited", StandardCharsets.UTF_8);
        Thread.sleep(120);   // 超过 check-interval（0.05s）

        assertThat(promptCatalog.get(PromptIds.INTENT_AGENT).system())
                .as("改完文件、不重启，下一次取模板就该是新的")
                .isEqualTo("external system edited");
    }
}
