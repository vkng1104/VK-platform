package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionRevokeReason;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthenticationSessionRepository {
    AuthenticationSession create(AuthenticationSession session);

    Optional<AuthenticationSession> findById(UUID id);

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
}
