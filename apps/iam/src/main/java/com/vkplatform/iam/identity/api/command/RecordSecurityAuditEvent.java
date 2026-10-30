package com.vkplatform.iam.identity.api.command;

import com.vkplatform.iam.identity.api.SecurityAuditEventType;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RecordSecurityAuditEvent(
        SecurityAuditEventType type,
        UUID accountId,
        UUID targetId,
        String actorId,
        String requestId,
        Instant occurredAt
) {
    public RecordSecurityAuditEvent {
        Objects.requireNonNull(type);
        Objects.requireNonNull(occurredAt);
        actorId = optionalContext(actorId, "security audit actor");
        requestId = optionalContext(requestId, "security audit request ID");
    }

    private static String optionalContext(String value, String name) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.strip();
    }
}
