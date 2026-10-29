package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyOperationGuardEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

interface SpringDataSigningKeyOperationGuardRepository
        extends JpaRepository<SigningKeyOperationGuardEntity, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SigningKeyOperationGuardEntity> findLockedById(short id);
}
