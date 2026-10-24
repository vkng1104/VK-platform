package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

interface SpringDataOAuthAuthorizationRepository extends JpaRepository<OAuthAuthorizationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthAuthorizationEntity> findLockedById(String id);

    Optional<OAuthAuthorizationEntity> findByStateDigest(byte[] digest);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthAuthorizationEntity> findLockedByAuthorizationCodeDigest(byte[] digest);
}
