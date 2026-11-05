package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;

import java.time.Instant;
import java.util.UUID;

public interface OAuthAuthorizationRepository {
    /**
     * Locks the session's authorizations that are not invalidated yet and invalidates them with
     * the supplied time and reason. Authorizations that are already invalidated keep their values.
     *
     * @return the number of authorizations invalidated by this invocation
     */
    int invalidateAllForSession(
            UUID sessionId,
            OAuthAuthorizationInvalidationReason reason,
            Instant invalidatedAt
    );
}
