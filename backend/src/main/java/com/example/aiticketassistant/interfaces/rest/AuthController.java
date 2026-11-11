package com.example.aiticketassistant.interfaces.rest;

import com.example.aiticketassistant.application.auth.AuthService;
import com.example.aiticketassistant.domain.auth.AuthToken;
import com.example.aiticketassistant.infrastructure.web.AuthContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * 登录/登出（2026-11-09，演示级鉴权）。
 *
 * <p>**这不是真实登录**：没有密码/验证码，只校验客户 ID 存在。它解决的是"换个参数就读别人数据"的越权问题，
 * 不是"证明你是你"。真实项目请把这里换成统一登录/短信验证后再发令牌（见 §5.7）。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("customerId");
        AuthToken token = authService.login(raw == null ? "" : String.valueOf(raw));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token.token());
        response.put("customerId", token.customerId());
        response.put("expiresAt", token.expiresAt().toString());
        response.put("note", "演示级鉴权：仅校验客户存在，无密码；令牌有效期 24h，可用 /auth/logout 注销");
        return response;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                     ServerWebExchange exchange) {
        String token = null;
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authorization.substring(7).trim();
        } else if (exchange.getRequest().getQueryParams().getFirst("token") != null) {
            token = exchange.getRequest().getQueryParams().getFirst("token");
        }
        authService.logout(token);
        return Map.of("loggedOut", true);
    }

    /** 当前令牌代表哪个客户（前端启动时用来确认登录态）。 */
    @PostMapping("/whoami")
    public Map<String, Object> whoami(ServerWebExchange exchange) {
        return Map.of("customerId", AuthContext.customerId(exchange).orElse(""));
    }
}
