package com.vkplatform.iam.identity.api.event;

import java.time.Instant;
import java.util.UUID;

public record AccountAuthenticationInvalidated(
        UUID accountId,
        String actorId,
        Instant occurredAt,
        String requestId
) {
}
