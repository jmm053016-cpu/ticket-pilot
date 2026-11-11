package com.example.aiticketassistant.infrastructure.prompt;

import com.example.aiticketassistant.application.prompt.PromptCatalog;
import com.example.aiticketassistant.application.prompt.PromptIds;
import com.example.aiticketassistant.application.prompt.PromptReloadResult;
import com.example.aiticketassistant.application.prompt.PromptSourceInfo;
import com.example.aiticketassistant.application.prompt.PromptTemplate;
import com.example.aiticketassistant.infrastructure.config.AssistantProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 从 {@code classpath:prompts/}（可被外部目录覆盖）加载全部提示词模板，支持**热加载**。
 *
 * <p>加载来源优先级：**外部目录 {@code assistant.prompts.dir} → classpath**。
 * 外部目录专治"改提示词要重启"：把 {@code PROMPTS_DIR} 指向 {@code backend/src/main/resources/prompts}，
 * 改完保存即生效，连 Build（复制到 {@code target/classes}）都不用。
 *
 * <p>三条不变式（改这个类之前先读完）：
 * <ol>
 *   <li><b>启动期缺文件 = 启动失败</b>：提示词是运行契约，不允许启动时悄悄退化成空字符串或旧硬编码文本。</li>
 *   <li><b>运行期重载失败 ≠ 服务不可用</b>：校验不过（缺文件/内容为空）时**保留上一版**并记 error，
 *       正在进行的对话继续用旧模板 —— 热加载是开发便利，不能变成事故源。</li>
 *   <li><b>读取口径统一</b>：UTF-8、{@code CRLF -> LF}、去尾部空白，避免编辑器换行设置影响模板内容。</li>
 * </ol>
 *
 * <p>热加载两种触发：{@code get()} 时按 {@code check-interval}（默认 1s）**懒检查**文件指纹（mtime + 大小），
 * 变了才真正重读；以及 {@link #reload()} 手动立即重载（{@code POST /admin/prompts/reload}）。
 * 用"懒检查 + 节流"而不是监听线程：不额外起线程、不会漏事件，一次 stat 的成本远低于一次问答。
 *
 * <p>**jar 内不可热加载**：{@code ClassPathResource.getFile()} 在胖 jar 里会抛异常，
 * 此时自动退化为"启动加载一次"，并提示改用 {@code PROMPTS_DIR}。
 */
@Component
public class ClasspathPromptCatalog implements PromptCatalog {
    private static final Logger log = LoggerFactory.getLogger(ClasspathPromptCatalog.class);
    private static final String PREFIX = "prompts/";
    private static final String SYSTEM_SUFFIX = ".system.md";
    private static final String USER_SUFFIX = ".user.md";
    private static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(1);

    private final Path externalDir;
    /**
     * 外部目录是否**权威**：目录里至少有一个 {@code <id>.system.md} 时，它就是这个项目的提示词来源，
     * 缺文件算配置错误（不再悄悄回落到 classpath）。
     *
     * <p>为什么不做"逐文件回落"：两个来源混着读，改完一处没生效时极难排查
     * （你以为改的是外部文件，实际生效的是 jar 里的旧副本）。目录整个不存在/为空时才算"没配"。
     */
    private final boolean externalAuthoritative;
    private final boolean hotReloadConfigured;
    private final Duration checkInterval;

    /** 当前生效的模板（整体替换，读侧无锁）。 */
    private volatile Map<String, PromptTemplate> templates;
    /** 上次成功加载时的文件指纹；失败时不更新，下个周期会重试。 */
    private volatile String loadedFingerprint;
    private volatile long lastCheckNanos;
    /** 最近一次重载错误（成功即清空）。 */
    private volatile String lastError;
    /** 已报过警的坏指纹，避免同一个坏状态每秒刷一条日志。 */
    private volatile String lastReportedFailure;
    /** jar 内运行时无法热加载（{@code getFile()} 不可用）。 */
    private volatile boolean hotReloadDisabled = false;

    /**
     * 容器装配用：**必须带 {@code @Autowired}**。
     *
     * <p>本类有两个构造函数（下面那个无参的是给测试的"只读 classpath"），Spring 遇到多个构造函数
     * 且都没标注时会**回落到无参构造函数** —— 于是 {@code assistant.prompts.*} 配了也白配（这个坑真机验过：
     * 设了 {@code PROMPTS_DIR} 却仍读 {@code target/classes}）。
     */
    @Autowired
    public ClasspathPromptCatalog(AssistantProperties properties) {
        this(properties == null ? null : resolveDir(properties.prompts()),
                properties == null || properties.prompts() == null || properties.prompts().hotReload(),
                properties == null || properties.prompts() == null || properties.prompts().checkInterval() == null
                        ? DEFAULT_CHECK_INTERVAL
                        : properties.prompts().checkInterval());
    }

    /** 只读 classpath、不热加载（测试用：断言"启动即固定"的旧行为）。 */
    public ClasspathPromptCatalog() {
        this(null, false, DEFAULT_CHECK_INTERVAL);
    }

    ClasspathPromptCatalog(Path externalDir, boolean hotReload, Duration checkInterval) {
        this.externalDir = externalDir;
        this.externalAuthoritative = externalDir != null && hasAnyPromptFile(externalDir);
        this.hotReloadConfigured = hotReload;
        this.checkInterval = checkInterval == null || checkInterval.isNegative() || checkInterval.isZero()
                ? DEFAULT_CHECK_INTERVAL
                : checkInterval;
        this.templates = loadAll(true);
        this.loadedFingerprint = templateFingerprint();
        if (externalDir != null) {
            log.info("提示词外部目录已启用（优先生效）：{}", externalDir);
        }
        if (hotReloadConfigured) {
            log.info("提示词热加载已启用（检查间隔 {}）：改文件后自动生效，或 POST /admin/prompts/reload 立即生效",
                    this.checkInterval);
        }
    }

    @Override
    public PromptTemplate get(String id) {
        maybeReload();
        PromptTemplate template = templates.get(id);
        if (template == null) {
            throw new IllegalArgumentException("未注册的提示词 ID: " + id + "，可用: " + templates.keySet());
        }
        return template;
    }

    @Override
    public PromptReloadResult reload() {
        synchronized (this) {
            return reloadLocked(templateFingerprint(), true);
        }
    }

    @Override
    public List<PromptSourceInfo> info() {
        List<PromptSourceInfo> infos = new ArrayList<>();
        for (String id : PromptIds.ALL) {
            infos.add(new PromptSourceInfo(
                    id,
                    describe(id + SYSTEM_SUFFIX),
                    describe(id + USER_SUFFIX),
                    !hotReloadDisabled && (externalDir != null || resourceFile(id + SYSTEM_SUFFIX) != null),
                    lastError));
        }
        return infos;
    }

    /** 懒加载入口：按 check-interval 节流，指纹没变就什么都不做。 */
    private void maybeReload() {
        if (!hotReloadConfigured || hotReloadDisabled) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastCheckNanos < checkInterval.toNanos()) {
            return;
        }
        lastCheckNanos = now;
        String fingerprint = templateFingerprint();
        if (fingerprint == null || fingerprint.equals(loadedFingerprint)) {
            return;
        }
        synchronized (this) {
            if (fingerprint.equals(loadedFingerprint)) {
                return;
            }
            reloadLocked(fingerprint, false);
        }
    }

    /**
     * 真正重读。失败时保留上一版。
     *
     * @param force true = 手动触发（指纹没变也重读）；false = 指纹变化触发的重载
     */
    private PromptReloadResult reloadLocked(String fingerprint, boolean force) {
        try {
            Map<String, PromptTemplate> reloaded = loadAll(false);
            templates = reloaded;
            loadedFingerprint = fingerprint;
            lastError = null;
            lastReportedFailure = null;
            log.info("提示词已重新加载（{} 个）：{}", reloaded.size(), reloaded.keySet());
            return PromptReloadResult.ok(reloaded.size(), List.copyOf(reloaded.keySet()));
        } catch (RuntimeException ex) {
            lastError = ex.getMessage();
            // 同一个坏状态只报一次，避免每秒刷日志；修好后（指纹变了）会再报
            if (fingerprint == null || !fingerprint.equals(lastReportedFailure)) {
                lastReportedFailure = fingerprint;
                log.error("提示词重新加载失败，继续使用上一版（对话不受影响）：{}", ex.getMessage());
            }
            return PromptReloadResult.failed(templates.size(), List.copyOf(templates.keySet()), ex.getMessage());
        }
    }

    /**
     * 文件指纹：每个提示词文件的 (来源, 大小, mtime)。
     *
     * <p>返回 null 表示"这个环境判断不了变化"（典型是胖 jar 里的 classpath 资源没有文件系统路径）。
     */
    private String templateFingerprint() {
        StringBuilder fingerprint = new StringBuilder();
        for (String id : PromptIds.ALL) {
            for (String suffix : List.of(SYSTEM_SUFFIX, USER_SUFFIX)) {
                Path file = sourceFile(id + suffix);
                if (file == null) {
                    return null;
                }
                fingerprint.append(file).append('|').append(sizeOf(file)).append('|').append(mtimeOf(file)).append(';');
            }
        }
        return fingerprint.toString();
    }

    /** 该模板文件实际从哪读（外部目录权威时只认外部目录）；都不可达返回 null。 */
    private Path sourceFile(String fileName) {
        if (externalDir != null) {
            Path candidate = externalDir.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            if (externalAuthoritative) {
                return null;   // 外部目录是权威来源：缺文件就是缺文件，不偷偷回落到 classpath
            }
        }
        Path resource = resourceFile(fileName);
        if (resource == null && hotReloadConfigured && !hotReloadDisabled) {
            hotReloadDisabled = true;
            log.info("提示词来自 jar 内 classpath，无法感知文件变化；"
                    + "要热加载请用 PROMPTS_DIR 指向外部目录（例如 backend/src/main/resources/prompts）");
        }
        return resource;
    }

    /** 目录里只要有一个 {@code <id>.system.md} 就认为"这个目录是本项目的提示词来源"。 */
    private static boolean hasAnyPromptFile(Path dir) {
        for (String id : PromptIds.ALL) {
            if (Files.isRegularFile(dir.resolve(id + SYSTEM_SUFFIX))) {
                return true;
            }
        }
        return false;
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return -1;
        }
    }

    private long mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ex) {
            return -1;
        }
    }

    /** classpath 资源对应的真实文件（jar 内为 null）。 */
    private Path resourceFile(String fileName) {
        try {
            return new ClassPathResource(PREFIX + fileName).getFile().toPath();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private String describe(String fileName) {
        Path file = sourceFile(fileName);
        return file != null ? file.toAbsolutePath().toString() : "classpath:" + PREFIX + fileName + "（不可热加载）";
    }

    /**
     * 全量加载 + 校验。
     *
     * @param failFast 启动期 = true（缺文件直接让应用起不来）；运行期重载 = false（异常交给调用方保留旧版）
     */
    private Map<String, PromptTemplate> loadAll(boolean failFast) {
        Map<String, PromptTemplate> loaded = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> blank = new ArrayList<>();
        for (String id : PromptIds.ALL) {
            String system = read(id + SYSTEM_SUFFIX, missing);
            String userTemplate = read(id + USER_SUFFIX, missing);
            if (system == null || userTemplate == null) {
                continue;
            }
            if (system.isBlank() || userTemplate.isBlank()) {
                blank.add(id);
                continue;
            }
            loaded.put(id, new PromptTemplate(id, system, userTemplate));
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("缺少提示词模板文件: " + missing + "。提示词位于 backend/src/main/resources/"
                    + PREFIX + "，命名规则为 <id>.system.md 与 <id>.user.md，id 见 PromptIds。");
        }
        if (!blank.isEmpty()) {
            throw new IllegalStateException("提示词模板内容为空: " + blank);
        }
        if (failFast) {
            log.info("已加载 {} 个提示词模板: {}", loaded.size(), loaded.keySet());
        }
        return Map.copyOf(loaded);
    }

    private String read(String fileName, List<String> missing) {
        Path file = sourceFile(fileName);
        if (file != null) {
            try {
                return normalize(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new IllegalStateException("读取提示词文件失败: " + file, ex);
            }
        }
        if (externalAuthoritative) {
            // 外部目录是权威来源：缺文件就是缺文件，不能悄悄读 classpath 里的旧副本
            missing.add(fileName);
            return null;
        }
        ClassPathResource resource = new ClassPathResource(PREFIX + fileName);
        if (!resource.exists()) {
            missing.add(fileName);
            return null;
        }
        try (InputStream input = resource.getInputStream()) {
            return normalize(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException("读取提示词模板失败: " + fileName, ex);
        }
    }

    private String normalize(String raw) {
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static Path resolveDir(AssistantProperties.Prompts prompts) {
        if (prompts == null || prompts.dir() == null || prompts.dir().isBlank()) {
            return null;
        }
        Path dir = Path.of(prompts.dir().trim());
        if (!Files.isDirectory(dir)) {
            log.warn("assistant.prompts.dir 配置的目录不存在，忽略并用 classpath：{}", dir.toAbsolutePath());
            return null;
        }
        return dir;
    }
}
