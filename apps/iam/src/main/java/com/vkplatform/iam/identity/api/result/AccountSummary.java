package com.vkplatform.iam.identity.api.result;

import java.time.Instant;
import java.util.UUID;

public record AccountSummary(
        UUID id,
        String status,
        Instant tokenNotBefore,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
