package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

interface SpringDataOAuthClientRepository extends JpaRepository<OAuthClientEntity, String> {
    Optional<OAuthClientEntity> findByClientId(String clientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthClientEntity> findLockedByClientId(String clientId);
}
