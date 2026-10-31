package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import com.vkplatform.iam.session.domain.SessionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

interface SpringDataAuthenticationSessionRepository extends JpaRepository<SessionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SessionEntity> findLockedById(UUID id);

    Optional<SessionEntity> findByIdAndAccountId(UUID id, UUID accountId);

    Page<SessionEntity> findByAccountIdAndStatusAndExpiresAtAfter(
            UUID accountId,
            SessionStatus status,
            Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SessionEntity> findLockedByAccountIdAndStatusOrderByIdAsc(
            UUID accountId,
            SessionStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SessionEntity> findLockedByAccountIdOrderByIdAsc(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SessionEntity> findLockedByAccountIdAndIdInOrderByIdAsc(
            UUID accountId,
            Collection<UUID> ids
    );
}
