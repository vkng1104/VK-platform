package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.identity.api.event.SecurityAuditEvent;
import com.vkplatform.iam.identity.api.event.SecurityAuditType;
import com.vkplatform.iam.session.infrastructure.config.SessionSecurityMetrics;
import com.vkplatform.iam.session.application.port.out.SessionRepository;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
import com.vkplatform.iam.session.domain.UserSession;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JpaSessionRepository implements SessionRepository {
    private final SpringDataSessionRepository sessions;
    private final SpringDataOAuthAuthorizationRepository authorizations;
    private final SecurityAuditOperations audit;
    private final SessionSecurityMetrics metrics;

    JpaSessionRepository(
            SpringDataSessionRepository sessions,
            SpringDataOAuthAuthorizationRepository authorizations,
            SecurityAuditOperations audit,
            SessionSecurityMetrics metrics
    ) {
        this.sessions = sessions;
        this.authorizations = authorizations;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public UserSession create(
            UserSession session,
            String requestId
    ) {
        sessions.save(new SessionEntity(session));
        appendEvent(
                SecurityAuditType.SESSION_CREATED,
                session.accountId(),
                session.id(),
                session.accountId().toString(),
                requestId,
                session.createdAt()
        );
        return session;
    }

    @Override
    @Transactional(readOnly = true)
    public UserSession findOwned(UUID accountId, UUID sessionId) {
        return sessions.findByIdAndAccountId(sessionId, accountId)
                .map(SessionEntity::toDomain)
                .orElseThrow(SessionFailure::sessionNotFound);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSession> findActiveByAccount(UUID accountId, Instant now) {
        return sessions.findByAccountIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        accountId,
                        SessionStatus.ACTIVE,
                        now
                ).stream()
                .map(SessionEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public boolean revokeOwned(
            UUID accountId,
            UUID sessionId,
            SessionRevokeReason reason,
            Instant now,
            String requestId
    ) {
        SessionEntity entity = sessions.findLockedById(sessionId).orElse(null);
        if (entity == null || !entity.accountId().equals(accountId)) {
            return false;
        }
        UserSession current = entity.toDomain();
        UserSession revoked = current.revoke(reason, now);
        if (revoked.status() != current.status()) {
            entity.apply(revoked);
            invalidateAuthorizations(
                    sessionId,
                    now,
                    OAuthAuthorizationInvalidationReason.fromSessionReason(reason)
            );
            appendEvent(
                    SecurityAuditType.SESSION_REVOKED,
                    accountId,
                    sessionId,
                    accountId.toString(),
                    requestId,
                    now
            );
            metrics.sessionRevoked();
        }
        return true;
    }

    @Override
    @Transactional
    public int revokeAll(
            UUID accountId,
            UUID exceptSessionId,
            String actorId,
            SessionRevokeReason reason,
            Instant now,
            String requestId
    ) {
        List<SessionEntity> active = exceptSessionId == null
                ? sessions.findLockedByAccountIdAndStatusOrderByIdAsc(accountId, SessionStatus.ACTIVE)
                : sessions.findLockedByAccountIdAndStatusAndIdNotOrderByIdAsc(
                        accountId,
                        SessionStatus.ACTIVE,
                        exceptSessionId
                );
        for (SessionEntity entity : active) {
            entity.apply(entity.toDomain().revoke(reason, now));
            invalidateAuthorizations(
                    entity.id(),
                    now,
                    OAuthAuthorizationInvalidationReason.fromSessionReason(reason)
            );
        }
        if (!active.isEmpty()) {
            appendEvent(
                    SecurityAuditType.ALL_SESSIONS_REVOKED,
                    accountId,
                    accountId,
                    actorId,
                    requestId,
                    now
            );
            active.forEach(ignored -> metrics.sessionRevoked());
        }
        return active.size();
    }

    private void appendEvent(
            SecurityAuditType eventType,
            UUID accountId,
            UUID targetId,
            String actorId,
            String requestId,
            Instant now
    ) {
        audit.append(new SecurityAuditEvent(
                eventType,
                accountId,
                targetId,
                actorId,
                requestId,
                now
        ));
    }

    private void invalidateAuthorizations(
            UUID sessionId,
            Instant now,
            OAuthAuthorizationInvalidationReason reason
    ) {
        for (OAuthAuthorizationEntity authorization
                : authorizations.findLockedBySessionIdAndInvalidatedAtIsNull(sessionId)) {
            authorization.invalidate(now, reason);
        }
    }
}
