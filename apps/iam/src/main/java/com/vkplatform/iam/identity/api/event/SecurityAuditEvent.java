package com.vkplatform.iam.identity.api.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SecurityAuditEvent(
        SecurityAuditType type,
        UUID accountId,
        UUID targetId,
        String actorId,
        String requestId,
        Instant occurredAt
) {
    public SecurityAuditEvent {
        Objects.requireNonNull(type);
        Objects.requireNonNull(occurredAt);
    }
}
