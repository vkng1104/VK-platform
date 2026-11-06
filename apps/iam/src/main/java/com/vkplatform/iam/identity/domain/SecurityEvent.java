package com.vkplatform.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An append-only audit entry about an account. {@code actorId} is null when the user acted. */
public record SecurityEvent(
        UUID id,
        SecurityEventType type,
        UUID accountId,
        UUID targetId,
        String actorId,
        String requestId,
        Instant occurredAt
) {
    public SecurityEvent {
        Objects.requireNonNull(id);
        Objects.requireNonNull(type);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(targetId);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(occurredAt);
    }
}
