package com.vkplatform.iam.verification.api.result;

import java.time.Instant;
import java.util.UUID;

public record VerificationChallengeStarted(
        UUID id,
        String maskedEmail,
        Instant expiresAt,
        Instant resendAfter
) {
}
