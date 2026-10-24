package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientOperationGuardEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

interface SpringDataOAuthClientOperationGuardRepository
        extends JpaRepository<OAuthClientOperationGuardEntity, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OAuthClientOperationGuardEntity> findLockedById(short id);
}
