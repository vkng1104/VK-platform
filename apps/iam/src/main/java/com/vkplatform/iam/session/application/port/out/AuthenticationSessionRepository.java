package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.domain.AuthenticationSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    /** Locks the session row until the caller's transaction ends. */
    Optional<AuthenticationSession> findLockedById(UUID id);

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

    /** Stores a refresh the caller applied with {@code touch}. The session row is locked first. */
    void recordRefresh(AuthenticationSession refreshed);

    /** Stores a compromise the caller applied. The session row is locked before it is written. */
    void recordCompromise(AuthenticationSession compromised);

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
