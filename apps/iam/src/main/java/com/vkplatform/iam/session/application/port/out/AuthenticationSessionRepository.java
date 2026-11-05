package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionRevokeReason;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthenticationSessionRepository {
    AuthenticationSession create(AuthenticationSession session);

    Optional<AuthenticationSession> findById(UUID id);

    /**
     * Locks every session of the account, whatever its status, in ID order. The row locks last
     * until the caller's transaction ends.
     */
    List<AuthenticationSession> findAllLockedByAccountId(UUID accountId);

    /**
     * Stores a revocation the caller has already applied to the session. The session row is
     * locked before it is written.
     */
    void recordRevocation(AuthenticationSession revoked);

    /**
     * Revokes a session only when it belongs to the supplied account.
     *
     * @return {@code true} when the session exists and belongs to the account, including when an
     * idempotent terminal state means no new transition occurs; {@code false} for both a missing
     * session and a session owned by another account
     */
    boolean revokeByIdForAccount(
            UUID accountId,
            UUID sessionId,
            SessionRevokeReason reason,
            Instant revokedAt
    );

    /**
     * Revokes every active session belonging to the supplied account.
     *
     * @return the number of active sessions transitioned by this invocation
     */
    int revokeAll(
            UUID accountId,
            SessionRevokeReason reason,
            Instant revokedAt
    );
}
