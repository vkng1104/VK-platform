package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.domain.SessionStatus;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataSessionRepository extends JpaRepository<SessionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SessionEntity> findLockedById(UUID id);

    Optional<SessionEntity> findByIdAndAccountId(UUID id, UUID accountId);

    List<SessionEntity> findByAccountIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
            UUID accountId,
            SessionStatus status,
            Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SessionEntity> findLockedByAccountIdAndStatusOrderByIdAsc(UUID accountId, SessionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SessionEntity> findLockedByAccountIdAndStatusAndIdNotOrderByIdAsc(
            UUID accountId,
            SessionStatus status,
            UUID excludedId
    );
}
