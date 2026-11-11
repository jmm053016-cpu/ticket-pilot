package com.example.aiticketassistant.infrastructure.web;

import com.example.aiticketassistant.application.auth.AuthService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 鉴权过滤器（2026-11-09）。
 *
 * <p>三件事：
 * <ol>
 *   <li>校验令牌（`Authorization: Bearer`、`X-Customer-Token`、或 SSE 用的 `?token=`），
 *       把客户身份放进 {@link AuthContext}。</li>
 *   <li>没有令牌直接 **401**（不再"默认 CUST-1001"）。</li>
 *   <li>{@code assistant.auth.enabled=false} 时整体放行 —— 演示/测试模式，行为与改造前一致。</li>
 * </ol>
 *
 * <p>为什么令牌还能放 query：`EventSource` 无法自定义请求头，SSE 只能把令牌放 URL。
 * 代价是令牌可能进访问日志，所以令牌是**短期**的（默认 24h）且可注销。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthWebFilter implements WebFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthWebFilter.class);

    /** 不需要令牌的路径：登录本身、健康检查、商品目录（首页展示用）、OpenAPI 文档。 */
    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/auth/", "/products", "/health", "/actuator", "/v3/api-docs", "/swagger-ui", "/webjars");

    private final AuthService authService;

    public AuthWebFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!authService.enabled()) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getURI().getPath();
        if (isPublic(path) || HttpMethodHeadOrOptions(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        Optional<String> token = tokenOf(exchange.getRequest());
        if (token.isEmpty()) {
            return unauthorized(exchange, "缺少访问令牌，请先登录（POST /auth/login）");
        }
        Optional<String> customerId = authService.resolve(token.get());
        if (customerId.isEmpty()) {
            return unauthorized(exchange, "访问令牌无效或已过期，请重新登录");
        }
        exchange.getAttributes().put(AuthContext.CUSTOMER_ID_ATTRIBUTE, customerId.get());
        return chain.filter(exchange);
    }

    private boolean isPublic(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return true;
        }
        return PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private boolean HttpMethodHeadOrOptions(ServerHttpRequest request) {
        return request.getMethod() != null
                && (request.getMethod().matches("HEAD") || request.getMethod().matches("OPTIONS"));
    }

    private Optional<String> tokenOf(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String value = authorization.substring(7).trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
        String header = request.getHeaders().getFirst("X-Customer-Token");
        if (header != null && !header.isBlank()) {
            return Optional.of(header.trim());
        }
        // SSE：EventSource 不能带头，只能走 query
        String query = request.getQueryParams().getFirst("token");
        return query == null || query.isBlank() ? Optional.empty() : Optional.of(query.trim());
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        log.debug("拒绝未授权请求: {} {}", exchange.getRequest().getMethod(), exchange.getRequest().getURI().getPath());
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
