package com.example.aiticketassistant.application.maintenance;

import com.example.aiticketassistant.domain.auth.AuthTokenRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据保留与清理（2026-11-09）。
 *
 * <p>为什么要有：几类表只增不减 —— 过期待确认单、闲置购物车、老会话、审计 trace。
 * 演示环境跑几周就开始堆积，MySQL 里 `workflow_trace_steps` 涨得最快。
 *
 * <p>**保留策略（可配）**：
 * <ul>
 *   <li>待确认单：`EXPIRED`/`PENDING` 且已过期超过 {@code retention.confirmation-days} 天 → 删（含明细行）。</li>
 *   <li>购物车：`updated_at` 早于 {@code retention.cart-days} 天 → 删（车主页会显示为空，符合直觉）。</li>
 *   <li>会话：`updated_at` 早于 {@code retention.session-days} 天 → 连消息一起删。</li>
 *   <li>审计：trace / steps / tool_invocations 早于 {@code retention.trace-days} 天 → 删。</li>
 *   <li>登录令牌：过期或已注销超过 1 天 → 删（保留一天便于排查"刚失效"的问题）。</li>
 * </ul>
 *
 * <p>业务数据（订单、工单、退款/退货申请、优惠券）**不清理** —— 那是账，不是缓存。
 */
@Service
public class RetentionCleanupService {
    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    private final JdbcTemplate jdbc;
    private final AuthTokenRepository authTokenRepository;
    private final int confirmationDays;
    private final int cartDays;
    private final int sessionDays;
    private final int traceDays;

    public RetentionCleanupService(JdbcTemplate jdbc,
                                   AuthTokenRepository authTokenRepository,
                                   @Value("${assistant.retention.confirmation-days:7}") int confirmationDays,
                                   @Value("${assistant.retention.cart-days:30}") int cartDays,
                                   @Value("${assistant.retention.session-days:30}") int sessionDays,
                                   @Value("${assistant.retention.trace-days:14}") int traceDays) {
        this.jdbc = jdbc;
        this.authTokenRepository = authTokenRepository;
        this.confirmationDays = Math.max(1, confirmationDays);
        this.cartDays = Math.max(1, cartDays);
        this.sessionDays = Math.max(1, sessionDays);
        this.traceDays = Math.max(1, traceDays);
    }

    /** 跑一轮清理，返回每类删了多少（便于断言与日志核对）。 */
    @Transactional
    public CleanupReport runOnce() {
        Instant now = Instant.now();
        int confirmations = cleanupConfirmations(now.minus(Duration.ofDays(confirmationDays)));
        int cartItems = cleanupCart(now.minus(Duration.ofDays(cartDays)));
        int sessions = cleanupSessions(now.minus(Duration.ofDays(sessionDays)));
        int traces = cleanupTraces(now.minus(Duration.ofDays(traceDays)));
        int tokens = authTokenRepository.deleteOlderThan(now.minus(Duration.ofDays(1)));

        CleanupReport report = new CleanupReport(confirmations, cartItems, sessions, traces, tokens);
        log.info("数据清理完成: {}", report);
        return report;
    }

    private int cleanupConfirmations(Instant threshold) {
        // 先删明细再删主表（外键）
        int details = jdbc.update("""
                DELETE FROM order_confirmation_items
                 WHERE confirmation_id IN (
                       SELECT id FROM order_confirmations
                        WHERE status IN ('PENDING', 'EXPIRED') AND created_at < ?)
                """, threshold);
        int headers = jdbc.update(
                "DELETE FROM order_confirmations WHERE status IN ('PENDING', 'EXPIRED') AND created_at < ?", threshold);
        return Math.max(headers, details == 0 ? headers : Math.max(details, headers));
    }

    private int cleanupCart(Instant threshold) {
        return jdbc.update("DELETE FROM cart_items WHERE updated_at < ?", threshold);
    }

    private int cleanupSessions(Instant threshold) {
        int messages = jdbc.update("""
                DELETE FROM assistant_messages
                 WHERE session_id IN (SELECT id FROM assistant_sessions WHERE updated_at < ?)
                """, threshold);
        int sessions = jdbc.update("DELETE FROM assistant_sessions WHERE updated_at < ?", threshold);
        return sessions > 0 ? sessions : messages;
    }

    private int cleanupTraces(Instant threshold) {
        jdbc.update("""
                DELETE FROM workflow_trace_steps
                 WHERE trace_id IN (SELECT id FROM workflow_traces WHERE started_at < ?)
                """, threshold);
        int traces = jdbc.update("DELETE FROM workflow_traces WHERE started_at < ?", threshold);
        jdbc.update("DELETE FROM tool_invocations WHERE created_at < ?", threshold);
        return traces;
    }

    /** 一轮清理的结果（单位：行）。 */
    public record CleanupReport(int confirmations, int cartItems, int sessions, int traces, int authTokens) {}
}
