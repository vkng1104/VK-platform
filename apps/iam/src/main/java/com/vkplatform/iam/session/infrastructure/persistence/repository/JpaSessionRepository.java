package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.SessionRepository;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
import com.vkplatform.iam.session.domain.UserSession;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionSecurityEventEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JpaSessionRepository implements SessionRepository {
    private final SpringDataSessionRepository sessions;
    private final SpringDataOAuthAuthorizationRepository authorizations;
    private final SpringDataSessionSecurityEventRepository events;

    JpaSessionRepository(
            SpringDataSessionRepository sessions,
            SpringDataOAuthAuthorizationRepository authorizations,
            SpringDataSessionSecurityEventRepository events
    ) {
        this.sessions = sessions;
        this.authorizations = authorizations;
        this.events = events;
    }

    @Override
    @Transactional
    public UserSession create(
            UserSession session,
            String requestId
    ) {
        sessions.save(new SessionEntity(session));
        appendEvent(
                "session_created",
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
            invalidateAuthorizations(sessionId, now, reason.wireValue());
            appendEvent(
                    "session_revoked",
                    accountId,
                    sessionId,
                    accountId.toString(),
                    requestId,
                    now
            );
        }
        return true;
    }

    @Override
    @Transactional
    public int revokeAll(
            UUID accountId,
            UUID exceptSessionId,
            SessionRevokeReason reason,
            Instant now,
            String requestId
    ) {
        List<SessionEntity> active = exceptSessionId == null
                ? sessions.findLockedByAccountIdAndStatus(accountId, SessionStatus.ACTIVE)
                : sessions.findLockedByAccountIdAndStatusAndIdNot(
                        accountId,
                        SessionStatus.ACTIVE,
                        exceptSessionId
                );
        for (SessionEntity entity : active) {
            entity.apply(entity.toDomain().revoke(reason, now));
            invalidateAuthorizations(entity.id(), now, reason.wireValue());
        }
        if (!active.isEmpty()) {
            appendEvent(
                    "all_sessions_revoked",
                    accountId,
                    accountId,
                    accountId.toString(),
                    requestId,
                    now
            );
        }
        return active.size();
    }

    private void appendEvent(
            String eventType,
            UUID accountId,
            UUID targetId,
            String actorId,
            String requestId,
            Instant now
    ) {
        events.save(new SessionSecurityEventEntity(
                UUID.randomUUID(),
                eventType,
                accountId,
                targetId,
                actorId,
                requestId,
                now
        ));
    }

    private void invalidateAuthorizations(UUID sessionId, Instant now, String reason) {
        for (OAuthAuthorizationEntity authorization
                : authorizations.findLockedBySessionIdAndInvalidatedAtIsNull(sessionId)) {
            authorization.invalidate(now, reason);
        }
    }
}
