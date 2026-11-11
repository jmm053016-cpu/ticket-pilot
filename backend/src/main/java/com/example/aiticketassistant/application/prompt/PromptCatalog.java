package com.example.aiticketassistant.application.prompt;

import java.util.List;

/**
 * 提示词读取端口。应用层只依赖该接口，提示词从哪里加载（classpath、外部目录、配置中心）
 * 由基础设施层决定，避免应用层反向依赖基础设施。
 */
public interface PromptCatalog {
    /**
     * @param id 见 {@link PromptIds}
     * @return 对应模板，保证 system 与 userTemplate 均非空
     * @throws IllegalArgumentException id 未注册
     */
    PromptTemplate get(String id);

    /**
     * 立即重新加载全部提示词（热加载入口）。
     *
     * <p>默认实现返回"不支持" —— 只读实现（例如从配置中心拉的）不必实现它。
     * 实现方必须保证**失败不影响正在运行的对话**（保留上一版）。
     */
    default PromptReloadResult reload() {
        return PromptReloadResult.failed(0, List.of(), "该 PromptCatalog 实现不支持重新加载");
    }

    /** 各提示词的来源与状态（诊断用）。 */
    default List<PromptSourceInfo> info() {
        return List.of();
    }
}
