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
        Instant expiresAt
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
                expiresAt
        );
    }
}
