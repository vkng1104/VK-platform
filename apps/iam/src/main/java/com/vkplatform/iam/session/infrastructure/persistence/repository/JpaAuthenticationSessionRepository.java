package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaAuthenticationSessionRepository implements AuthenticationSessionRepository {
    private final SpringDataAuthenticationSessionRepository sessions;
    private final SpringDataOAuthAuthorizationRepository authorizations;

    JpaAuthenticationSessionRepository(
            SpringDataAuthenticationSessionRepository sessions,
            SpringDataOAuthAuthorizationRepository authorizations
    ) {
        this.sessions = sessions;
        this.authorizations = authorizations;
    }

    @Override
    @Transactional
    public AuthenticationSession create(AuthenticationSession session) {
        return sessions.save(new SessionEntity(session)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticationSession> findById(UUID id) {
        return sessions.findById(id).map(SessionEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticationSession> findOwned(UUID accountId, UUID sessionId) {
        return sessions.findByIdAndAccountId(sessionId, accountId).map(SessionEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public SessionPage findActiveByAccount(
            UUID accountId,
            Instant now,
            int page,
            int size
    ) {
        Page<SessionEntity> result = sessions.findByAccountIdAndStatusAndExpiresAtAfter(
                accountId,
                SessionStatus.ACTIVE,
                now,
                PageRequest.of(page, size, Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                ))
        );
        return new SessionPage(
                result.getContent().stream().map(SessionEntity::toDomain).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Override
    @Transactional
    public boolean revokeOwned(
            UUID accountId,
            UUID sessionId,
            SessionRevokeReason reason,
            Instant revokedAt
    ) {
        SessionEntity entity = sessions.findLockedById(sessionId).orElse(null);
        if (entity == null || !entity.toDomain().accountId().equals(accountId)) {
            return false;
        }
        entity.apply(entity.toDomain().revoke(reason, revokedAt));
        return true;
    }

    @Override
    @Transactional
    public int revokeAll(
            UUID accountId,
            SessionRevokeReason reason,
            Instant revokedAt
    ) {
        List<SessionEntity> active = sessions.findLockedByAccountIdAndStatusOrderByIdAsc(
                accountId,
                SessionStatus.ACTIVE
        );
        active.forEach(entity -> entity.apply(entity.toDomain().revoke(reason, revokedAt)));
        return active.size();
    }

    @Override
    @Transactional
    public boolean revokeOwnedAndInvalidate(
            UUID accountId,
            UUID sessionId,
            SessionRevokeReason reason,
            Instant revokedAt
    ) {
        SessionEntity entity = sessions.findLockedById(sessionId).orElse(null);
        if (entity == null || !entity.accountId().equals(accountId)) {
            return false;
        }
        AuthenticationSession revoked = entity.toDomain().revoke(reason, revokedAt);
        entity.apply(revoked);
        invalidateAuthorizations(
                sessionId,
                revokedAt,
                OAuthAuthorizationInvalidationReason.fromSessionReason(revoked.revokeReason())
        );
        return true;
    }

    @Override
    @Transactional
    public RevokeResult revokeOwnedFromCurrent(
            UUID accountId,
            UUID currentSessionId,
            UUID targetSessionId,
            String expectedClientId,
            SessionRevokeReason reason,
            Instant revokedAt
    ) {
        List<SessionEntity> locked = sessions.findLockedByAccountIdAndIdInOrderByIdAsc(
                accountId,
                Set.copyOf(List.of(currentSessionId, targetSessionId))
        );
        SessionEntity current = locked.stream()
                .filter(session -> session.id().equals(currentSessionId))
                .findFirst()
                .orElse(null);
        if (current == null || !current.toDomain().canAuthorize(
                accountId,
                expectedClientId,
                revokedAt
        )) {
            return RevokeResult.CURRENT_UNAVAILABLE;
        }
        SessionEntity target = locked.stream()
                .filter(session -> session.id().equals(targetSessionId))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return RevokeResult.TARGET_NOT_FOUND;
        }
        AuthenticationSession revoked = target.toDomain().revoke(reason, revokedAt);
        target.apply(revoked);
        invalidateAuthorizations(
                targetSessionId,
                revokedAt,
                OAuthAuthorizationInvalidationReason.fromSessionReason(revoked.revokeReason())
        );
        return RevokeResult.REVOKED;
    }

    @Override
    @Transactional
    public RevokeAllResult revokeAllFromCurrent(
            UUID accountId,
            UUID currentSessionId,
            String expectedClientId,
            SessionRevokeReason reason,
            Instant revokedAt
    ) {
        List<SessionEntity> owned = sessions.findLockedByAccountIdOrderByIdAsc(accountId);
        SessionEntity current = owned.stream()
                .filter(session -> session.id().equals(currentSessionId))
                .findFirst()
                .orElse(null);
        if (current == null || !current.toDomain().canAuthorize(
                accountId,
                expectedClientId,
                revokedAt
        )) {
            return new RevokeAllResult(false, 0);
        }
        List<SessionEntity> active = owned.stream()
                .filter(session -> session.toDomain().status() == SessionStatus.ACTIVE)
                .toList();
        for (SessionEntity entity : active) {
            entity.apply(entity.toDomain().revoke(reason, revokedAt));
            invalidateAuthorizations(
                    entity.id(),
                    revokedAt,
                    OAuthAuthorizationInvalidationReason.fromSessionReason(reason)
            );
        }
        return new RevokeAllResult(true, active.size());
    }

    private void invalidateAuthorizations(
            UUID sessionId,
            Instant invalidatedAt,
            OAuthAuthorizationInvalidationReason reason
    ) {
        authorizations.findLockedBySessionIdAndInvalidatedAtIsNull(sessionId)
                .forEach(authorization -> authorization.invalidate(invalidatedAt, reason));
    }
}
