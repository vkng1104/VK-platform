package com.vkplatform.iam.identity.api.result;

import java.time.Instant;
import java.util.UUID;

public record AuthenticationGrantIssued(
        UUID accountId,
        String grant,
        String audience,
        Instant expiresAt
) {
}
