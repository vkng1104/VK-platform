package com.vkplatform.iam.verification.infrastructure.persistence.repository;

import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationOperationGuardEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

interface SpringDataEmailVerificationOperationGuardRepository extends
        JpaRepository<EmailVerificationOperationGuardEntity, Short> {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationOperationGuardEntity> findById(Short id);
}
