package com.vkplatform.iam.verification.domain;

import java.time.Instant;
import java.util.UUID;

public record VerificationChallenge(
        UUID id,
        VerificationPurpose purpose,
        byte[] emailFingerprint,
        byte[] requesterFingerprint,
        byte[] otpDigest,
        int maxAttempts,
        Instant expiresAt,
        Instant resendNotBefore,
        Instant createdAt
) {
}
