package com.vkplatform.iam.session.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UserSession(
        UUID id,
        UUID accountId,
        String clientId,
        SessionStatus status,
        long version,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant compromisedAt,
        SessionRevokeReason revokeReason
) {
    public UserSession {
        Objects.requireNonNull(id);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(lastSeenAt);
        Objects.requireNonNull(expiresAt);
        if (clientId.isBlank() || clientId.length() > 128 || version < 0
                || lastSeenAt.isBefore(createdAt) || !expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("session state is invalid");
        }
        if (status == SessionStatus.ACTIVE
                && (revokedAt != null || compromisedAt != null || revokeReason != null)) {
            throw new IllegalArgumentException("active session cannot have terminal state");
        }
        if (status == SessionStatus.REVOKED && (revokedAt == null || revokeReason == null)) {
            throw new IllegalArgumentException("revoked session state is incomplete");
        }
        if (status == SessionStatus.COMPROMISED
                && (revokedAt == null || compromisedAt == null
                || revokeReason != SessionRevokeReason.REFRESH_TOKEN_REUSED)) {
            throw new IllegalArgumentException("compromised session state is incomplete");
        }
    }

    public static UserSession create(
            UUID id,
            UUID accountId,
            String clientId,
            Instant now,
            Instant expiresAt
    ) {
        return new UserSession(
                id,
                accountId,
                clientId,
                SessionStatus.ACTIVE,
                0,
                now,
                now,
                expiresAt,
                null,
                null,
                null
        );
    }

    public boolean canUse(String expectedClientId, Instant now) {
        return status == SessionStatus.ACTIVE
                && clientId.equals(expectedClientId)
                && now.isBefore(expiresAt);
    }

    public UserSession touch(Instant now) {
        requireTimestamp(now);
        if (status != SessionStatus.ACTIVE || !now.isBefore(expiresAt)) {
            throw SessionFailure.invalidRefreshToken();
        }
        return new UserSession(
                id, accountId, clientId, status, version, createdAt, now, expiresAt,
                revokedAt, compromisedAt, revokeReason
        );
    }

    public UserSession revoke(SessionRevokeReason reason, Instant now) {
        Objects.requireNonNull(reason);
        requireTimestamp(now);
        if (status != SessionStatus.ACTIVE) {
            return this;
        }
        return new UserSession(
                id, accountId, clientId, SessionStatus.REVOKED, version, createdAt,
                lastSeenAt, expiresAt, now, null, reason
        );
    }

    public UserSession compromise(Instant now) {
        requireTimestamp(now);
        if (status == SessionStatus.COMPROMISED) {
            return this;
        }
        return new UserSession(
                id, accountId, clientId, SessionStatus.COMPROMISED, version, createdAt,
                lastSeenAt, expiresAt, now, now, SessionRevokeReason.REFRESH_TOKEN_REUSED
        );
    }

    private void requireTimestamp(Instant now) {
        Objects.requireNonNull(now);
        if (now.isBefore(lastSeenAt)) {
            throw new IllegalArgumentException("session change precedes its current state");
        }
    }
}
