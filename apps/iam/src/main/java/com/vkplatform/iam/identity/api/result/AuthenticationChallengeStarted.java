package com.vkplatform.iam.identity.api.result;

import java.time.Instant;
import java.util.UUID;

public record AuthenticationChallengeStarted(
        UUID id,
        String maskedEmail,
        Instant expiresAt,
        Instant resendAfter
) {
}
