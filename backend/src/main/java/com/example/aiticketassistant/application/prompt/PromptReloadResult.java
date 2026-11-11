package com.example.aiticketassistant.application.prompt;

import java.util.List;

/**
 * 一次提示词重新加载的结果（供运维/调试接口回显）。
 *
 * @param success   是否成功；失败时 {@code templates} 保留加载前的数量，运行中的对话不受影响
 * @param templates 当前生效的模板数量
 * @param ids       当前生效的提示词 ID
 * @param error     失败原因；成功为 null
 */
public record PromptReloadResult(boolean success, int templates, List<String> ids, String error) {

    public static PromptReloadResult ok(int templates, List<String> ids) {
        return new PromptReloadResult(true, templates, List.copyOf(ids), null);
    }

    public static PromptReloadResult failed(int templates, List<String> ids, String error) {
        return new PromptReloadResult(false, templates, List.copyOf(ids), error);
    }
}
