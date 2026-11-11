package com.example.aiticketassistant.application.prompt;

/**
 * 单个提示词的来源信息（诊断用：这个 prompt 到底是从哪读的、能不能热加载）。
 *
 * @param id          提示词 ID
 * @param systemFile  system 模板的来源描述（外部文件路径或 {@code classpath:...}）
 * @param userFile    user 模板的来源描述
 * @param hotReloadable 该来源是否支持热加载（jar 内的 classpath 资源做不到）
 * @param lastError   最近一次重新加载的错误；没有则为 null
 */
public record PromptSourceInfo(String id, String systemFile, String userFile, boolean hotReloadable, String lastError) {}
