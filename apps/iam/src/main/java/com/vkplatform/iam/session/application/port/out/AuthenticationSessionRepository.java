package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionRevokeReason;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface AuthenticationSessionRepository {
    AuthenticationSession create(AuthenticationSession session);

    Optional<AuthenticationSession> findById(UUID id);

    Optional<AuthenticationSession> findOwned(UUID accountId, UUID sessionId);

    SessionPage findActiveByAccount(
            UUID accountId,
            Instant now,
            int page,
            int size
    );

    boolean revokeOwned(
            UUID accountId,
            UUID sessionId,
            SessionRevokeReason reason,
            Instant revokedAt
    );

    int revokeAll(
            UUID accountId,
            SessionRevokeReason reason,
            Instant revokedAt
    );

    boolean revokeOwnedAndInvalidate(
            UUID accountId,
            UUID sessionId,
            SessionRevokeReason reason,
            Instant revokedAt
    );

    RevokeResult revokeOwnedFromCurrent(
            UUID accountId,
            UUID currentSessionId,
            UUID targetSessionId,
            String expectedClientId,
            SessionRevokeReason reason,
            Instant revokedAt
    );

    RevokeAllResult revokeAllFromCurrent(
            UUID accountId,
            UUID currentSessionId,
            String expectedClientId,
            SessionRevokeReason reason,
            Instant revokedAt
    );

    enum RevokeResult {
        REVOKED,
        UNCHANGED,
        TARGET_NOT_FOUND,
        CURRENT_UNAVAILABLE
    }

    record RevokeAllResult(boolean currentAvailable, int revokedCount) {
    }

    record SessionPage(
            List<AuthenticationSession> sessions,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        public SessionPage {
            sessions = List.copyOf(sessions);
        }
    }
}
