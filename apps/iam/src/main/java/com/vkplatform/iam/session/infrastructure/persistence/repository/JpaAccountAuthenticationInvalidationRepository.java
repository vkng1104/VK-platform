package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.AccountAuthenticationInvalidationRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public class JpaAccountAuthenticationInvalidationRepository
        implements AccountAuthenticationInvalidationRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            JpaAccountAuthenticationInvalidationRepository.class
    );

    private final SpringDataAuthenticationSessionRepository sessions;
    private final SpringDataOAuthAuthorizationRepository authorizations;

    JpaAccountAuthenticationInvalidationRepository(
            SpringDataAuthenticationSessionRepository sessions,
            SpringDataOAuthAuthorizationRepository authorizations
    ) {
        this.sessions = sessions;
        this.authorizations = authorizations;
    }

    @Override
    @Transactional
    public void invalidateAccount(UUID accountId, Instant occurredAt) {
        for (SessionEntity session : sessions.findLockedByAccountIdOrderByIdAsc(accountId)) {
            Instant effectiveInvalidationAt = effectiveInvalidationAt(session.toDomain(), occurredAt);
            session.apply(session.toDomain().revoke(
                    SessionRevokeReason.ACCOUNT_STATE_CHANGED,
                    effectiveInvalidationAt
            ));
            authorizations.findLockedBySessionIdAndInvalidatedAtIsNull(session.id())
                    .forEach(authorization -> authorization.invalidate(
                            effectiveInvalidationAt,
                            OAuthAuthorizationInvalidationReason.ACCOUNT_STATE_CHANGED
                    ));
        }
    }

    // Status changes sample their time after locking the account, so a session refreshed later
    // than occurredAt should be exceptional (clock skew, imported data, or a lock-order
    // regression). Keep revoked_at >= last_refreshed_at, and make the clamp visible.
    private static Instant effectiveInvalidationAt(AuthenticationSession session, Instant occurredAt) {
        if (!occurredAt.isBefore(session.lastRefreshedAt())) {
            return occurredAt;
        }
        LOGGER.warn(
                "Clamped account invalidation to the session's last refresh; sessionId={}, occurredAt={}, lastRefreshedAt={}",
                session.id(),
                occurredAt,
                session.lastRefreshedAt()
        );
        return session.lastRefreshedAt();
    }
}
