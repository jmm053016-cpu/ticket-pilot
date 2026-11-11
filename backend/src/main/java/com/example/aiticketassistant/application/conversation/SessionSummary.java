package com.example.aiticketassistant.application.conversation;

import java.time.Instant;

/**
 * 会话列表项：给"会话侧栏"用，一条会话一行。
 *
 * @param sessionId    会话 ID
 * @param preview      首条用户消息的截断摘要（用于列表展示）
 * @param messageCount 该会话累计消息条数
 * @param createdAt    创建时间
 * @param updatedAt    最近活跃时间
 */
public record SessionSummary(
        String sessionId,
        String preview,
        long messageCount,
        Instant createdAt,
        Instant updatedAt) {}
