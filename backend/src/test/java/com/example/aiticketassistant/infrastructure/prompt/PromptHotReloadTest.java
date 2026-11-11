package com.example.aiticketassistant.infrastructure.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.aiticketassistant.application.prompt.PromptIds;
import com.example.aiticketassistant.application.prompt.PromptReloadResult;
import com.example.aiticketassistant.application.prompt.PromptSourceInfo;
import com.example.aiticketassistant.application.prompt.PromptTemplate;
import com.example.aiticketassistant.infrastructure.config.AssistantProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 提示词热加载（原 §11.1 第 8 条）。
 *
 * <p>要命的两件事必须同时成立：
 * <ol>
 *   <li><b>改文件就生效</b>（含外部目录优先、自动重载、手动 reload 三条路径）；</li>
 *   <li><b>改坏了不能把服务带崩</b> —— 缺文件/空文件时保留上一版并报错，对话继续用旧模板。</li>
 * </ol>
 * 这两条都有测试盯着；另外"启动期缺文件必须失败"也保留（启动失败 ≠ 运行期降级）。
 */
class PromptHotReloadTest {

    @TempDir
    Path promptsDir;

    @BeforeEach
    void writeAllPrompts() throws IOException {
        for (String id : PromptIds.ALL) {
            write(id, "system v1 for " + id, "user v1 for " + id + " {{userMessage}}");
        }
    }

    private void write(String id, String system, String userTemplate) throws IOException {
        Files.writeString(promptsDir.resolve(id + ".system.md"), system, StandardCharsets.UTF_8);
        Files.writeString(promptsDir.resolve(id + ".user.md"), userTemplate, StandardCharsets.UTF_8);
    }

    private ClasspathPromptCatalog catalog(boolean hotReload, Duration interval) {
        return new ClasspathPromptCatalog(promptsDir, hotReload, interval);
    }

    @Test
    void externalDirectoryOverridesClasspathAndIsReportedByInfo() {
        ClasspathPromptCatalog catalog = catalog(false, Duration.ofSeconds(1));

        PromptTemplate template = catalog.get(PromptIds.DIRECT_ANSWER);

        assertThat(template.system()).isEqualTo("system v1 for " + PromptIds.DIRECT_ANSWER);
        assertThat(template.user(Map.of("userMessage", "你好"))).contains("user v1").contains("你好");
        List<PromptSourceInfo> infos = catalog.info();
        assertThat(infos).hasSize(PromptIds.ALL.size());
        assertThat(infos).allSatisfy(info -> {
            assertThat(info.systemFile()).contains(promptsDir.toString());
            assertThat(info.hotReloadable()).isTrue();
            assertThat(info.lastError()).isNull();
        });
    }

    @Test
    void manualReloadPicksUpEditsImmediately() throws IOException {
        ClasspathPromptCatalog catalog = catalog(false, Duration.ofSeconds(1));
        assertThat(catalog.get(PromptIds.INTENT_AGENT).system()).isEqualTo("system v1 for " + PromptIds.INTENT_AGENT);

        write(PromptIds.INTENT_AGENT, "system v2", "user v2 {{userMessage}}");
        PromptReloadResult result = catalog.reload();

        assertThat(result.success()).isTrue();
        assertThat(result.templates()).isEqualTo(PromptIds.ALL.size());
        assertThat(catalog.get(PromptIds.INTENT_AGENT).system()).isEqualTo("system v2");
    }

    @Test
    void autoReloadHappensOnNextGetAfterInterval() throws IOException, InterruptedException {
        // 间隔给 20ms，模拟"改完保存 → 下一次问答就用新模板"
        ClasspathPromptCatalog catalog = catalog(true, Duration.ofMillis(20));
        assertThat(catalog.get(PromptIds.ORDER_AGENT).system()).isEqualTo("system v1 for " + PromptIds.ORDER_AGENT);

        write(PromptIds.ORDER_AGENT, "system v2", "user v2 {{userMessage}}");
        Thread.sleep(40);

        assertThat(catalog.get(PromptIds.ORDER_AGENT).system())
                .as("改文件后不重启、不手动 reload，下一次取模板就该是新的")
                .isEqualTo("system v2");
    }

    @Test
    void brokenEditKeepsLastGoodVersionAndReportsError() throws IOException {
        ClasspathPromptCatalog catalog = catalog(false, Duration.ofSeconds(1));

        // 删掉一个文件：重新加载必须失败，但已加载的模板继续可用
        Files.delete(promptsDir.resolve(PromptIds.KNOWLEDGE_AGENT + ".user.md"));
        PromptReloadResult result = catalog.reload();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("缺少提示词模板文件");
        assertThat(result.templates()).isEqualTo(PromptIds.ALL.size());
        assertThat(catalog.get(PromptIds.KNOWLEDGE_AGENT).system()).isEqualTo("system v1 for " + PromptIds.KNOWLEDGE_AGENT);
        assertThat(catalog.info()).allSatisfy(info -> assertThat(info.lastError()).isNotNull());

        // 修好后 reload 应恢复成功并清掉错误
        write(PromptIds.KNOWLEDGE_AGENT, "system fixed", "user fixed {{userMessage}}");
        PromptReloadResult recovered = catalog.reload();

        assertThat(recovered.success()).isTrue();
        assertThat(catalog.get(PromptIds.KNOWLEDGE_AGENT).system()).isEqualTo("system fixed");
        assertThat(catalog.info()).allSatisfy(info -> assertThat(info.lastError()).isNull());
    }

    @Test
    void blankTemplateIsRejectedOnReload() throws IOException {
        ClasspathPromptCatalog catalog = catalog(false, Duration.ofSeconds(1));

        write(PromptIds.DIRECT_ANSWER, "", "user v2 {{userMessage}}");

        assertThat(catalog.reload().error()).contains("内容为空");
        assertThat(catalog.get(PromptIds.DIRECT_ANSWER).system()).isEqualTo("system v1 for " + PromptIds.DIRECT_ANSWER);
    }

    @Test
    void startupStillFailsFastWhenFilesAreMissing() throws IOException {
        // 启动期缺文件 = 启动失败（不变式不能因为"支持热加载"就退化成静默降级）
        Files.delete(promptsDir.resolve(PromptIds.DIRECT_ANSWER + ".system.md"));

        assertThatThrownBy(() -> catalog(false, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少提示词模板文件");
    }

    @Test
    void classpathOnlyCatalogLoadsAtStartupAndNeverReloads() {
        // 无参构造 = 旧的只读行为：不感知文件变化（生产/测试默认）
        ClasspathPromptCatalog catalog = new ClasspathPromptCatalog();

        assertThat(PromptIds.ALL).allSatisfy(id -> assertThat(catalog.get(id).system()).isNotBlank());
        assertThat(catalog.reload().success()).as("手动 reload 仍然可用").isTrue();
    }

    @Test
    void nonExistentDirFallsBackToClasspathInsteadOfFailing() {
        AssistantProperties.Prompts prompts = new AssistantProperties.Prompts(
                promptsDir.resolve("does-not-exist").toString(), true, Duration.ofSeconds(1));
        AssistantProperties properties = new AssistantProperties(null, null, prompts, null, null);

        ClasspathPromptCatalog catalog = new ClasspathPromptCatalog(properties);

        assertThat(catalog.get(PromptIds.DIRECT_ANSWER).system()).isNotBlank();
        assertThat(catalog.info()).isNotEmpty();
    }
}
