package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.infrastructure.persistence.entity.IdentityOperationGuardEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

interface SpringDataIdentityOperationGuardRepository extends JpaRepository<IdentityOperationGuardEntity, Short> {
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<IdentityOperationGuardEntity> findById(Short id);
}
