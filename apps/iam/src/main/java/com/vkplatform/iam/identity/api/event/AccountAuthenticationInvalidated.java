package com.vkplatform.iam.identity.api.event;

import java.time.Instant;
import java.util.UUID;

public record AccountAuthenticationInvalidated(
        UUID accountId,
        Instant occurredAt,
        String requestId
) {
}
