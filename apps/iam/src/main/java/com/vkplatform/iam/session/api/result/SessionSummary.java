package com.vkplatform.iam.session.api.result;

import java.time.Instant;
import java.util.UUID;

public record SessionSummary(
        UUID id,
        String clientId,
        String status,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant compromisedAt,
        String revokeReason
) {
}
