package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthRefreshTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface SpringDataOAuthRefreshTokenRepository extends JpaRepository<OAuthRefreshTokenEntity, UUID> {
    @Query("select token.authorizationId from OAuthRefreshTokenEntity token where token.tokenDigest = :digest")
    Optional<String> findAuthorizationIdByTokenDigest(@Param("digest") byte[] digest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthRefreshTokenEntity> findLockedByTokenDigest(byte[] tokenDigest);

    Optional<OAuthRefreshTokenEntity> findFirstByAuthorizationIdOrderByIssuedAtDesc(String authorizationId);
}
