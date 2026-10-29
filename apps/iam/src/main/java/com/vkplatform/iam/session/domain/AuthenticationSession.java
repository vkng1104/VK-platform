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
        Instant lastRefreshedAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant compromisedAt,
        SessionRevokeReason revokeReason
) {
    public AuthenticationSession {
        Objects.requireNonNull(id);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(clientId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(lastRefreshedAt);
        Objects.requireNonNull(expiresAt);
        if (clientId.isBlank() || clientId.length() > 128 || !clientId.equals(clientId.strip())) {
            throw new IllegalArgumentException("session client ID is invalid");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("session expiry must follow creation");
        }
        if (lastRefreshedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("session refresh precedes creation");
        }
        if (status == SessionStatus.ACTIVE
                && (revokedAt != null || compromisedAt != null || revokeReason != null)) {
            throw new IllegalArgumentException("active session cannot have revocation state");
        }
        if (status == SessionStatus.REVOKED
                && (revokedAt == null || compromisedAt != null || revokeReason == null
                || revokedAt.isBefore(createdAt))) {
            throw new IllegalArgumentException("revoked session state is incomplete");
        }
        if (status == SessionStatus.COMPROMISED
                && (revokedAt == null || compromisedAt == null
                || revokeReason != SessionRevokeReason.REFRESH_TOKEN_REUSED
                || revokedAt.isBefore(createdAt) || compromisedAt.isBefore(revokedAt))) {
            throw new IllegalArgumentException("compromised session state is incomplete");
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
                createdAt,
                expiresAt,
                null,
                null,
                null
        );
    }

    public AuthenticationSession touch(Instant now) {
        Objects.requireNonNull(now);
        if (status != SessionStatus.ACTIVE || !now.isBefore(expiresAt)) {
            throw new IllegalStateException("session cannot be refreshed");
        }
        if (now.isBefore(lastRefreshedAt)) {
            throw new IllegalArgumentException("session refresh precedes its current state");
        }
        return new AuthenticationSession(
                id,
                accountId,
                clientId,
                status,
                createdAt,
                now,
                expiresAt,
                revokedAt,
                compromisedAt,
                revokeReason
        );
    }

    public AuthenticationSession revoke(SessionRevokeReason reason, Instant now) {
        Objects.requireNonNull(reason);
        Objects.requireNonNull(now);
        if (status != SessionStatus.ACTIVE) {
            return this;
        }
        if (now.isBefore(lastRefreshedAt)) {
            throw new IllegalArgumentException("session revocation precedes creation");
        }
        return new AuthenticationSession(
                id,
                accountId,
                clientId,
                SessionStatus.REVOKED,
                createdAt,
                lastRefreshedAt,
                expiresAt,
                now,
                null,
                reason
        );
    }

    public AuthenticationSession compromise(Instant now) {
        Objects.requireNonNull(now);
        if (status == SessionStatus.COMPROMISED || status == SessionStatus.REVOKED) {
            return this;
        }
        if (now.isBefore(lastRefreshedAt)) {
            throw new IllegalArgumentException("session compromise precedes its current state");
        }
        return new AuthenticationSession(
                id,
                accountId,
                clientId,
                SessionStatus.COMPROMISED,
                createdAt,
                lastRefreshedAt,
                expiresAt,
                now,
                now,
                SessionRevokeReason.REFRESH_TOKEN_REUSED
        );
    }

    public boolean canAuthorize(UUID expectedAccountId, String expectedClientId, Instant now) {
        return accountId.equals(expectedAccountId)
                && clientId.equals(expectedClientId)
                && status == SessionStatus.ACTIVE
                && now.isBefore(expiresAt);
    }
}
