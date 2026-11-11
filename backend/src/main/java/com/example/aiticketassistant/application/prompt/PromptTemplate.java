package com.example.aiticketassistant.application.prompt;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一段外置提示词模板：{@code system} 直接使用，{@code userTemplate} 需按变量渲染。
 *
 * <p>占位符语法固定为 {@code {{name}}}。渲染只替换调用方显式传入的变量，
 * 不会对其它花括号做任何处理，因此提示词里可以安全书写 JSON 示例（如
 * {@code {"tool_calls":[{"tool":"QUERY_ORDER"}]}}）。
 *
 * <p>渲染后若仍残留 {@code {{name}}}，说明调用点与模板的变量契约不一致，
 * 直接抛错而不是把半成品发给模型。
 */
public record PromptTemplate(String id, String system, String userTemplate) {
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_.]+)\\}\\}");

    public String user(Map<String, Object> variables) {
        String rendered = userTemplate == null ? "" : userTemplate;
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                rendered = rendered.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
            }
        }
        Matcher matcher = UNRESOLVED_PLACEHOLDER.matcher(rendered);
        if (matcher.find()) {
            throw new IllegalStateException(
                    "Prompt '%s' 存在未赋值占位符 {{%s}}；调用点传入的变量为 %s"
                            .formatted(id, matcher.group(1), variables == null ? "[]" : variables.keySet()));
        }
        return rendered;
    }
}
