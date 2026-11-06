package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.application.port.out.OAuthAuthorizationRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Ends every way an account can still authenticate after its status changes: revokes its active
 * sessions and invalidates the OAuth authorizations of all its sessions.
 */
public class AccountAuthenticationInvalidationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            AccountAuthenticationInvalidationService.class
    );

    private final AuthenticationSessionRepository sessions;
    private final OAuthAuthorizationRepository authorizations;

    public AccountAuthenticationInvalidationService(
            AuthenticationSessionRepository sessions,
            OAuthAuthorizationRepository authorizations
    ) {
        this.sessions = Objects.requireNonNull(sessions);
        this.authorizations = Objects.requireNonNull(authorizations);
    }

    /**
     * Locks the account's sessions first and their authorizations second, the same order as the
     * token endpoint, so the two paths cannot deadlock.
     */
    @Transactional
    public void invalidateAccount(UUID accountId, Instant occurredAt) {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(occurredAt);
        for (AuthenticationSession session : sessions.findAllLockedByAccountId(accountId)) {
            Instant invalidatedAt = effectiveInvalidationAt(session, occurredAt);
            if (session.status() == SessionStatus.ACTIVE) {
                sessions.recordRevocation(session.revoke(
                        SessionRevokeReason.ACCOUNT_STATE_CHANGED,
                        invalidatedAt
                ));
            }
            authorizations.invalidateAllForSession(
                    session.id(),
                    OAuthAuthorizationInvalidationReason.ACCOUNT_STATE_CHANGED,
                    invalidatedAt
            );
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
