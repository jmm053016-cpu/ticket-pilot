package com.example.aiticketassistant.infrastructure.persistence.jpa;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataAuthTokenJpaRepository extends JpaRepository<AuthTokenJpaEntity, String> {
    @Modifying
    @Query("DELETE FROM AuthTokenJpaEntity t WHERE t.expiresAt < :threshold OR (t.revoked = true AND t.createdAt < :threshold)")
    int deleteExpiredOrRevokedBefore(@Param("threshold") Instant threshold);
}
