package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataOAuthAuthorizationRepository extends JpaRepository<OAuthAuthorizationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthAuthorizationEntity> findLockedById(String id);

    Optional<OAuthAuthorizationEntity> findByStateDigest(byte[] digest);

    Optional<OAuthAuthorizationEntity> findByAccessTokenDigest(byte[] digest);

    Optional<AuthorizationLockTarget> findLockTargetById(String id);

    Optional<AuthorizationLockTarget> findLockTargetByAuthorizationCodeDigest(byte[] digest);

    @Query("select authorization.sessionId from OAuthAuthorizationEntity authorization where authorization.id = :id")
    Optional<UUID> findSessionIdById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<OAuthAuthorizationEntity> findLockedBySessionIdAndInvalidatedAtIsNull(UUID sessionId);

    /** Columns needed to lock the owning session without loading the authorization entity. */
    interface AuthorizationLockTarget {
        String getId();

        UUID getSessionId();

        String getPrincipalName();

        String getRegisteredClientId();

        String getAuthorizationGrantType();
    }
}
