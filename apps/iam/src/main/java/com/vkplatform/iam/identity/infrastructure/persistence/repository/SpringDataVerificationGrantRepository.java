package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.infrastructure.persistence.entity.VerificationGrantEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

interface SpringDataVerificationGrantRepository extends JpaRepository<VerificationGrantEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<VerificationGrantEntity> findByGrantDigest(byte[] grantDigest);
}
