package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthRefreshTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

interface SpringDataOAuthRefreshTokenRepository extends JpaRepository<OAuthRefreshTokenEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthRefreshTokenEntity> findLockedByTokenDigest(byte[] tokenDigest);

    Optional<OAuthRefreshTokenEntity> findFirstByAuthorizationIdOrderByIssuedAtDesc(String authorizationId);
}
