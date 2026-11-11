package com.example.aiticketassistant.infrastructure.repository;

import com.example.aiticketassistant.domain.auth.AuthToken;
import com.example.aiticketassistant.domain.auth.AuthTokenRepository;
import com.example.aiticketassistant.infrastructure.persistence.jpa.AuthTokenJpaEntity;
import com.example.aiticketassistant.infrastructure.persistence.jpa.SpringDataAuthTokenJpaRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaAuthTokenRepository implements AuthTokenRepository {
    private final SpringDataAuthTokenJpaRepository repository;

    public JpaAuthTokenRepository(SpringDataAuthTokenJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public AuthToken save(AuthToken token) {
        AuthTokenJpaEntity entity = new AuthTokenJpaEntity();
        entity.setToken(token.token());
        entity.setCustomerId(token.customerId());
        entity.setCreatedAt(token.createdAt());
        entity.setExpiresAt(token.expiresAt());
        entity.setRevoked(token.revoked());
        AuthTokenJpaEntity saved = repository.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthToken> findByToken(String token) {
        return repository.findById(token).map(this::toDomain);
    }

    @Override
    @Transactional
    public void revoke(String token) {
        repository.findById(token).ifPresent(entity -> {
            entity.setRevoked(true);
            repository.save(entity);
        });
    }

    @Override
    @Transactional
    public int deleteOlderThan(Instant threshold) {
        return repository.deleteExpiredOrRevokedBefore(threshold);
    }

    private AuthToken toDomain(AuthTokenJpaEntity entity) {
        return new AuthToken(entity.getToken(), entity.getCustomerId(), entity.getCreatedAt(),
                entity.getExpiresAt(), entity.isRevoked());
    }
}
