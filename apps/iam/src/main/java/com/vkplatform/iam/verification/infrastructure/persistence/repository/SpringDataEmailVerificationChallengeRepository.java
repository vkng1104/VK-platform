package com.vkplatform.iam.verification.infrastructure.persistence.repository;

import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

interface SpringDataEmailVerificationChallengeRepository extends
        JpaRepository<EmailVerificationChallengeEntity, UUID>,
        JpaSpecificationExecutor<EmailVerificationChallengeEntity> {

    Optional<EmailVerificationChallengeEntity> findFirstByEmailFingerprintOrderByCreatedAtDesc(
            byte[] emailFingerprint
    );

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationChallengeEntity> findById(UUID id);
}
