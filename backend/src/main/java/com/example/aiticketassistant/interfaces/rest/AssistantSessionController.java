package com.example.aiticketassistant.interfaces.rest;

import com.example.aiticketassistant.application.conversation.ConversationMemory;
import com.example.aiticketassistant.application.conversation.ConversationTurn;
import com.example.aiticketassistant.infrastructure.web.AuthContext;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * 会话查询接口：给前端"会话侧栏 + 历史回看"用，只读（删除除外）。
 *
 * <p>写入仍走 {@code GET /assistant/stream}（一轮结束后自动落库），这里不提供写操作。
 *
 * <p>**2026-11-09 起三个方法都以"令牌里的客户"为准**：请求参数 {@code customerId} 只在
 * 关闭鉴权（演示/测试）时兜底；且 {@code /{sessionId}/messages} 现在**校验归属** ——
 * 之前只要知道 sessionId 就能读别人的历史。
 */
@RestController
@RequestMapping("/assistant/sessions")
public class AssistantSessionController {
    private final ConversationMemory conversationMemory;

    public AssistantSessionController(ConversationMemory conversationMemory) {
        this.conversationMemory = conversationMemory;
    }

    /** 某客户最近的会话列表（按最近活跃时间倒序）。 */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "CUST-1001") String customerId,
                                          @RequestParam(defaultValue = "20") int limit,
                                          ServerWebExchange exchange) {
        String owner = AuthContext.resolveCustomer(exchange, customerId);
        return conversationMemory.recentSessions(owner, Math.max(1, Math.min(50, limit))).stream()
                .map(summary -> {
                    Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("sessionId", summary.sessionId());
                    item.put("preview", summary.preview());
                    item.put("messageCount", summary.messageCount());
                    item.put("createdAt", summary.createdAt() == null ? "" : summary.createdAt().toString());
                    item.put("updatedAt", summary.updatedAt() == null ? "" : summary.updatedAt().toString());
                    return item;
                })
                .toList();
    }

    /**
     * 删除一个会话及其消息（会话侧栏的删除按钮）。
     *
     * <p>审计用的 `workflow_traces` 不动：那是"发生过什么"的记录，不随对话删除。
     */
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(@PathVariable String sessionId,
                                      @RequestParam(defaultValue = "CUST-1001") String customerId,
                                      ServerWebExchange exchange) {
        String owner = AuthContext.resolveCustomer(exchange, customerId);
        boolean deleted = conversationMemory.deleteSession(sessionId, owner);
        return Map.of("sessionId", sessionId, "deleted", deleted);
    }

    /**
     * 某会话的历史轮次（时间正序，最多 50 轮），用于回看。
     *
     * <p>**必须校验归属**：不是自己的会话一律按"不存在"处理（404），连"存在但无权"都不透露。
     */
    @GetMapping("/{sessionId}/messages")
    public Map<String, Object> messages(@PathVariable String sessionId,
                                        @RequestParam(defaultValue = "50") int limit,
                                        @RequestParam(defaultValue = "CUST-1001") String customerId,
                                        ServerWebExchange exchange) {
        String owner = AuthContext.resolveCustomer(exchange, customerId);
        conversationMemory.requireOwnership(sessionId, owner);
        List<ConversationTurn> turns = conversationMemory.recentTurns(sessionId, Math.max(1, Math.min(50, limit)));
        return Map.of("sessionId", sessionId, "turnCount", turns.size(), "turns", turns);
    }
}
