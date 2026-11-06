package com.vkplatform.iam.verification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One email verification challenge as stored. A challenge starts pending, then its delivery is
 * marked sent or failed. A sent challenge accepts code attempts until it is verified, invalidated
 * by a newer challenge, expired, or out of attempts.
 */
public record VerificationChallenge(
        UUID id,
        VerificationPurpose purpose,
        byte[] emailFingerprint,
        byte[] requesterFingerprint,
        byte[] otpDigest,
        int attemptCount,
        int maxAttempts,
        Instant expiresAt,
        Instant resendNotBefore,
        EmailDeliveryStatus deliveryStatus,
        Instant sentAt,
        Instant verifiedAt,
        Instant invalidatedAt,
        Instant createdAt
) {
    public VerificationChallenge {
        emailFingerprint = emailFingerprint.clone();
        requesterFingerprint = requesterFingerprint == null ? null : requesterFingerprint.clone();
        otpDigest = otpDigest.clone();
    }

    /** A new challenge whose email has not been sent yet and that has no attempts. */
    public static VerificationChallenge pending(
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
        return new VerificationChallenge(
                id,
                purpose,
                emailFingerprint,
                requesterFingerprint,
                otpDigest,
                0,
                maxAttempts,
                expiresAt,
                resendNotBefore,
                EmailDeliveryStatus.PENDING,
                null,
                null,
                null,
                createdAt
        );
    }

    /** Counts one more wrong code. */
    public VerificationChallenge failAttempt() {
        if (attemptCount >= maxAttempts) {
            throw new IllegalStateException("verification challenge has no attempts left");
        }
        return new VerificationChallenge(
                id,
                purpose,
                emailFingerprint,
                requesterFingerprint,
                otpDigest,
                attemptCount + 1,
                maxAttempts,
                expiresAt,
                resendNotBefore,
                deliveryStatus,
                sentAt,
                verifiedAt,
                invalidatedAt,
                createdAt
        );
    }

    /** Records that the right code arrived at the given time. */
    public VerificationChallenge verify(Instant at) {
        Objects.requireNonNull(at);
        if (verifiedAt != null) {
            throw new IllegalStateException("verification challenge is already verified");
        }
        return new VerificationChallenge(
                id,
                purpose,
                emailFingerprint,
                requesterFingerprint,
                otpDigest,
                attemptCount,
                maxAttempts,
                expiresAt,
                resendNotBefore,
                deliveryStatus,
                sentAt,
                at,
                invalidatedAt,
                createdAt
        );
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
