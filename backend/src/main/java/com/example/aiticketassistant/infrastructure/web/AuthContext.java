package com.example.aiticketassistant.infrastructure.web;

import java.util.Optional;
import org.springframework.web.server.ServerWebExchange;

/**
 * 已认证客户的传递方式：过滤器把令牌解析出的 customerId 放进 exchange 属性，
 * 控制器一律从这里取，**不再采信请求参数里的 customerId**。
 */
public final class AuthContext {
    /** exchange 属性键。 */
    public static final String CUSTOMER_ID_ATTRIBUTE = "auth.customerId";

    /** 当前请求的已认证客户；鉴权关闭或未认证时为空。 */
    public static Optional<String> customerId(ServerWebExchange exchange) {
        if (exchange == null) {
            return Optional.empty();
        }
        Object value = exchange.getAttribute(CUSTOMER_ID_ATTRIBUTE);
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    /**
     * 解析"本次请求代表哪个客户"：有令牌就用令牌的，没有才退回参数（鉴权关闭的演示模式）。
     *
     * <p>这样控制器只写一行，鉴权开/关都不需要改控制器逻辑。
     */
    public static String resolveCustomer(ServerWebExchange exchange, String fallback) {
        return customerId(exchange).orElse(fallback);
    }

    private AuthContext() {}
}
