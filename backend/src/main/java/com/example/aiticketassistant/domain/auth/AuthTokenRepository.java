package com.example.aiticketassistant.domain.auth;

import java.util.Optional;

/** 令牌仓储端口。 */
public interface AuthTokenRepository {
    AuthToken save(AuthToken token);

    Optional<AuthToken> findByToken(String token);

    /** 注销：置 revoked=true（保留行，便于审计"这个令牌什么时候被用过"）。 */
    void revoke(String token);

    /** 清理过期/已注销且早于 givenInstant 的令牌（保留近期记录供排查）。 */
    int deleteOlderThan(java.time.Instant threshold);
}
