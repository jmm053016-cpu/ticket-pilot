package com.example.aiticketassistant.domain.auth;

import java.time.Instant;

/**
 * 登录令牌：把**客户身份**绑在令牌上，而不是绑在请求参数上。
 *
 * <p>这是本项目鉴权的关键一步：原先 `customerId` 由 query/body 传入，改个参数就能查别人的订单与会话。
 * 现在服务端只认令牌里的客户（见 {@code AuthWebFilter}），参数里的 `customerId` 不再具备授权含义。
 *
 * <p>演示环境的边界：`login` 只校验 customerId 存在，没有密码/短信验证 —— 属于"演示级鉴权"，
 * 它解决的是"越权访问"，不是"你是谁"（实名认证属于外部系统，见 §5.7）。
 */
public record AuthToken(String token, String customerId, Instant createdAt, Instant expiresAt, boolean revoked) {

    public boolean usable(Instant now) {
        return !revoked && expiresAt != null && expiresAt.isAfter(now);
    }
}
