package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.domain.SigningKeyStatus;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface SpringDataSigningKeyRepository extends JpaRepository<SigningKeyEntity, String> {
    Optional<SigningKeyEntity> findByStatus(SigningKeyStatus status);

    List<SigningKeyEntity> findByStatusAndPublishUntilAfterOrderByActivatedAtDesc(
            SigningKeyStatus status,
            Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SigningKeyEntity> findLockedByStatus(SigningKeyStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SigningKeyEntity> findLockedById(String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SigningKeyEntity> findByProtectionKeyVersionNotOrderByIdAsc(
            String version,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SigningKeyEntity> findLockedByStatusAndPublishUntilLessThanEqualOrderByIdAsc(
            SigningKeyStatus status,
            Instant now
    );
}
