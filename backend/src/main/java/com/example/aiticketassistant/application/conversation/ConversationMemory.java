package com.example.aiticketassistant.application.conversation;

import java.util.List;

/**
 * 多轮会话记忆端口。
 *
 * <p>读：取最近若干轮历史，供提示词注入；写：一轮结束后把「用户问 + 助手答」落库。
 * 实现方必须自行兜住异常——记忆缺失不能让回答失败。
 */
public interface ConversationMemory {

    /**
     * @param sessionId 会话 ID
     * @param maxTurns  最多返回多少轮（按时间正序，最近的在最后）
     * @return 历史轮次；无历史返回空列表，绝不返回 null
     */
    List<ConversationTurn> recentTurns(String sessionId, int maxTurns);

    /**
     * 记录一轮对话。会话不存在时自动创建，存在时刷新 updatedAt。
     *
     * @param userMessage      用户提问原文
     * @param assistantAnswer  助手最终回答全文
     */
    void recordTurn(String sessionId, String customerId, String userMessage, String assistantAnswer);

    /**
     * 删除一个会话及其全部消息（前端"会话侧栏"的删除按钮）。
     *
     * <p>只删**会话与消息**，不动 {@code workflow_traces} —— 那是审计日志，记录"发生过什么"，
     * 不因为用户删了对话就消失。**必须校验归属**：customerId 不匹配时拒绝删除。
     *
     * @param sessionId  会话 ID
     * @param customerId 当前客户；不匹配则拒绝
     * @return 会话存在并已删除时返回 true
     */
    boolean deleteSession(String sessionId, String customerId);

    /**
     * 列出某客户最近的会话（按最近活跃时间倒序），用于前端"会话侧栏"。
     *
     * @param customerId 客户 ID
     * @param limit      最多返回多少条
     * @return 会话摘要列表；无数据返回空列表，绝不返回 null
     */
    List<SessionSummary> recentSessions(String customerId, int limit);

    /**
     * 校验会话归属：不是该客户的会话一律按「不存在」处理。
     *
     * <p>为什么抛异常而不是返回 boolean：读历史是「要么能看、要么拒绝」的操作，调用方一行即可；
     * 越权时统一 404，<b>不透露「存在但无权」</b>（否则等于给出会话是否存在的探针）。
     *
     * @throws com.example.aiticketassistant.domain.shared.NotFoundException 会话不存在或不属于该客户
     */
    void requireOwnership(String sessionId, String customerId);
}
