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
    public VerificationChallenge {
        emailFingerprint = emailFingerprint.clone();
        requesterFingerprint = requesterFingerprint == null ? null : requesterFingerprint.clone();
        otpDigest = otpDigest.clone();
    }

    @Override
    public byte[] emailFingerprint() {
        return emailFingerprint.clone();
    }

    @Override
    public byte[] requesterFingerprint() {
        return requesterFingerprint == null ? null : requesterFingerprint.clone();
    }

    @Override
    public byte[] otpDigest() {
        return otpDigest.clone();
    }
}
