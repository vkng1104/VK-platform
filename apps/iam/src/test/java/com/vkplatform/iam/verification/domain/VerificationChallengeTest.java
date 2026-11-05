package com.vkplatform.iam.verification.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationChallengeTest {
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");

    @Test
    void protectsStoredDigestAndFingerprintArraysFromMutation() {
        byte[] emailFingerprint = bytes(1);
        byte[] requesterFingerprint = bytes(2);
        byte[] otpDigest = bytes(3);
        VerificationChallenge challenge = VerificationChallenge.pending(
                UUID.randomUUID(),
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                emailFingerprint,
                requesterFingerprint,
                otpDigest,
                5,
                Instant.parse("2026-10-08T02:05:00Z"),
                Instant.parse("2026-10-08T02:01:00Z"),
                NOW
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

    @Test
    void startsPendingWithoutAttemptsOrLaterTransitions() {
        VerificationChallenge challenge = pending(5);

        assertThat(challenge.attemptCount()).isZero();
        assertThat(challenge.deliveryStatus()).isEqualTo(EmailDeliveryStatus.PENDING);
        assertThat(challenge.sentAt()).isNull();
        assertThat(challenge.verifiedAt()).isNull();
        assertThat(challenge.invalidatedAt()).isNull();
        assertThat(VerificationChallenge.pending(
                UUID.randomUUID(),
                VerificationPurpose.SIGN_IN,
                bytes(1),
                null,
                bytes(3),
                5,
                NOW.plusSeconds(300),
                NOW.plusSeconds(60),
                NOW
        ).requesterFingerprint()).isNull();
    }

    @Test
    void countsFailedAttemptsUntilNoneAreLeft() {
        VerificationChallenge challenge = pending(2);

        VerificationChallenge exhausted = challenge.failAttempt().failAttempt();

        assertThat(exhausted.attemptCount()).isEqualTo(2);
        assertThat(exhausted.verifiedAt()).isNull();
        assertThat(challenge.attemptCount()).isZero();
        assertThatThrownBy(exhausted::failAttempt)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("verification challenge has no attempts left");
    }

    @Test
    void recordsTheVerificationTimeOnlyOnce() {
        VerificationChallenge verified = pending(5).failAttempt().verify(NOW.plusSeconds(30));

        assertThat(verified.verifiedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(verified.attemptCount()).isEqualTo(1);
        assertThatThrownBy(() -> verified.verify(NOW.plusSeconds(40)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("verification challenge is already verified");
        assertThatThrownBy(() -> pending(5).verify(null)).isInstanceOf(NullPointerException.class);
    }

    private static VerificationChallenge pending(int maxAttempts) {
        return VerificationChallenge.pending(
                UUID.randomUUID(),
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                bytes(1),
                bytes(2),
                bytes(3),
                maxAttempts,
                NOW.plusSeconds(300),
                NOW.plusSeconds(60),
                NOW
        );
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }
}
