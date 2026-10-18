package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataOAuthAuthorizationRepository extends JpaRepository<OAuthAuthorizationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthAuthorizationEntity> findLockedById(String id);

    @Query("select authorization.sessionId from OAuthAuthorizationEntity authorization where authorization.id = :id")
    Optional<UUID> findSessionIdById(@Param("id") String id);

    Optional<OAuthAuthorizationEntity> findByStateDigest(byte[] digest);

    Optional<OAuthAuthorizationEntity> findByAuthorizationCodeDigest(byte[] digest);

    Optional<OAuthAuthorizationEntity> findByAccessTokenDigest(byte[] digest);

    Optional<OAuthAuthorizationEntity> findByRefreshTokenDigest(byte[] digest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OAuthAuthorizationEntity> findLockedBySessionIdAndInvalidatedAtIsNull(UUID sessionId);
}
