package com.vkplatform.iam.verification.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationChallengeTest {
    @Test
    void protectsStoredDigestAndFingerprintArraysFromMutation() {
        byte[] emailFingerprint = bytes(1);
        byte[] requesterFingerprint = bytes(2);
        byte[] otpDigest = bytes(3);
        VerificationChallenge challenge = new VerificationChallenge(
                UUID.randomUUID(),
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                emailFingerprint,
                requesterFingerprint,
                otpDigest,
                5,
                Instant.parse("2026-10-08T02:05:00Z"),
                Instant.parse("2026-10-08T02:01:00Z"),
                Instant.parse("2026-10-08T02:00:00Z")
        );

        emailFingerprint[0] = 9;
        requesterFingerprint[0] = 9;
        otpDigest[0] = 9;
        challenge.emailFingerprint()[1] = 9;
        challenge.requesterFingerprint()[1] = 9;
        challenge.otpDigest()[1] = 9;

        assertThat(challenge.emailFingerprint()).containsOnly((byte) 1);
        assertThat(challenge.requesterFingerprint()).containsOnly((byte) 2);
        assertThat(challenge.otpDigest()).containsOnly((byte) 3);
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }
}
