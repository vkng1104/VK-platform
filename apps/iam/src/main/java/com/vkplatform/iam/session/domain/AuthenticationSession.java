package com.vkplatform.iam.session.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuthenticationSession(
        UUID id,
        UUID accountId,
        String clientId,
        SessionStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        SessionRevokeReason revokeReason
) {
    public AuthenticationSession {
        Objects.requireNonNull(id);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(expiresAt);
        if (clientId.isBlank() || clientId.length() > 128 || !clientId.equals(clientId.strip())) {
            throw new IllegalArgumentException("session client ID is invalid");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("session expiry must follow creation");
        }
        if (status == SessionStatus.ACTIVE && (revokedAt != null || revokeReason != null)) {
            throw new IllegalArgumentException("active session cannot have revocation state");
        }
        if (status == SessionStatus.REVOKED
                && (revokedAt == null || revokeReason == null || revokedAt.isBefore(createdAt))) {
            throw new IllegalArgumentException("revoked session state is incomplete");
        }
    }

    public static AuthenticationSession create(
            UUID id,
            UUID accountId,
            String clientId,
            Instant createdAt,
            Instant expiresAt
    ) {
        return new AuthenticationSession(
                id,
                accountId,
                clientId,
                SessionStatus.ACTIVE,
                createdAt,
                expiresAt,
                null,
                null
        );
    }

    public AuthenticationSession revoke(SessionRevokeReason reason, Instant now) {
        Objects.requireNonNull(reason);
        Objects.requireNonNull(now);
        if (status == SessionStatus.REVOKED) {
            return this;
        }
        if (now.isBefore(createdAt)) {
            throw new IllegalArgumentException("session revocation precedes creation");
        }
        return new AuthenticationSession(
                id,
                accountId,
                clientId,
                SessionStatus.REVOKED,
                createdAt,
                expiresAt,
                now,
                reason
        );
    }

    public boolean canAuthorize(UUID expectedAccountId, String expectedClientId, Instant now) {
        return accountId.equals(expectedAccountId)
                && clientId.equals(expectedClientId)
                && status == SessionStatus.ACTIVE
                && now.isBefore(expiresAt);
    }
}
