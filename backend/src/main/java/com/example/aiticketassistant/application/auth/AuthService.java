package com.example.aiticketassistant.application.auth;

import com.example.aiticketassistant.domain.auth.AuthToken;
import com.example.aiticketassistant.domain.auth.AuthTokenRepository;
import com.example.aiticketassistant.domain.customer.AccountDirectory;
import com.example.aiticketassistant.domain.shared.DomainException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 鉴权服务（2026-11-09，演示级）。
 *
 * <p>为什么做：原先全站无鉴权 —— `customerId` 由请求参数传入，把参数改成别人的 ID
 * 就能读别人的会话历史、查别人的订单。现在 `customerId` 与令牌绑定，**服务端不再采信参数**。
 *
 * <p>**边界（必须写在文档里，别当成真实登录）**：`login` 只校验客户存在，没有密码/验证码。
 * 也就是说"谁能登录成 CUST-1001"取决于谁知道这个 ID —— 这解决了**越权访问**（一个客户读不到另一个客户的数据），
 * 没解决**身份认证**（证明你是你）。真实上线要接统一登录 / OAuth / 短信验证，见 §5.7。
 */
@Service
public class AuthService {
    private static final Pattern CUSTOMER_ID = Pattern.compile("^CUST-[A-Za-z0-9-]{1,40}$");

    private final AuthTokenRepository tokenRepository;
    private final AccountDirectory accountDirectory;
    private final boolean enabled;
    private final Duration ttl;

    public AuthService(AuthTokenRepository tokenRepository,
                       AccountDirectory accountDirectory,
                       @Value("${assistant.auth.enabled:true}") boolean enabled,
                       @Value("${assistant.auth.token-ttl-hours:24}") long ttlHours) {
        this.tokenRepository = tokenRepository;
        this.accountDirectory = accountDirectory;
        this.enabled = enabled;
        this.ttl = Duration.ofHours(Math.max(1, ttlHours));
    }

    public boolean enabled() {
        return enabled;
    }

    /** 登录：校验客户存在 → 发令牌。同一客户可持有多个令牌（多标签页/多设备）。 */
    @Transactional
    public AuthToken login(String customerId) {
        String normalized = customerId == null ? "" : customerId.trim().toUpperCase();
        if (!CUSTOMER_ID.matcher(normalized).matches()) {
            throw new DomainException("客户 ID 格式不对（应形如 CUST-1001）: " + customerId);
        }
        // 演示环境只有两个种子客户有地址/支付数据，用"有没有档案"当作"客户存在"的最低判据
        boolean exists = !accountDirectory.findAddresses(normalized).isEmpty()
                || !accountDirectory.findPaymentMethods(normalized).isEmpty();
        if (!exists) {
            throw new DomainException("客户不存在: " + normalized);
        }
        Instant now = Instant.now();
        String token = "tk_" + UUID.randomUUID().toString().replace("-", "");
        return tokenRepository.save(new AuthToken(token, normalized, now, now.plus(ttl), false));
    }

    /** 令牌 → 客户 ID；无效/过期/已注销返回空。 */
    @Transactional(readOnly = true)
    public Optional<String> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return tokenRepository.findByToken(token.trim())
                .filter(candidate -> candidate.usable(Instant.now()))
                .map(AuthToken::customerId);
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            tokenRepository.revoke(token.trim());
        }
    }
}
