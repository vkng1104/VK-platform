package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.UserSession;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SessionRepository {
    UserSession create(UserSession session, String requestId);

    UserSession findOwned(UUID accountId, UUID sessionId);

    List<UserSession> findActiveByAccount(UUID accountId, Instant now);

    boolean revokeOwned(
            UUID accountId,
            UUID sessionId,
            SessionRevokeReason reason,
            Instant now,
            String requestId
    );

    int revokeAll(
            UUID accountId,
            UUID exceptSessionId,
            SessionRevokeReason reason,
            Instant now,
            String requestId
    );

}
