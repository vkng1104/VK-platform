package com.vkplatform.iam.verification.infrastructure.persistence.entity;

import com.vkplatform.iam.verification.domain.EmailDeliveryStatus;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationChallengeEntityTest {
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");

    @Test
    void mapsTheDomainChallengeBothWays() {
        VerificationChallenge challenge = new VerificationChallenge(
                UUID.randomUUID(),
                VerificationPurpose.SIGN_IN,
                bytes(1),
                bytes(2),
                bytes(3),
                2,
                5,
                NOW.plusSeconds(300),
                NOW.plusSeconds(60),
                EmailDeliveryStatus.SENT,
                NOW.plusSeconds(1),
                NOW.plusSeconds(2),
                NOW.plusSeconds(3),
                NOW
        );

        assertThat(new EmailVerificationChallengeEntity(challenge).toDomain())
                .usingRecursiveComparison()
                .isEqualTo(challenge);
        assertThat(new EmailVerificationChallengeEntity(pending()).toDomain())
                .usingRecursiveComparison()
                .isEqualTo(pending());
    }

    @Test
    void appliesAttemptsAndVerificationButNotIdentityChanges() {
        VerificationChallenge challenge = pending();
        EmailVerificationChallengeEntity entity = new EmailVerificationChallengeEntity(challenge);
        VerificationChallenge verified = challenge.failAttempt().verify(NOW.plusSeconds(10));

        entity.apply(verified);

        assertThat(entity.toDomain().attemptCount()).isEqualTo(1);
        assertThat(entity.toDomain().verifiedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThatThrownBy(() -> entity.apply(VerificationChallenge.pending(
                UUID.randomUUID(),
                challenge.purpose(),
                challenge.emailFingerprint(),
                challenge.requesterFingerprint(),
                challenge.otpDigest(),
                challenge.maxAttempts(),
                challenge.expiresAt(),
                challenge.resendNotBefore(),
                challenge.createdAt()
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entity.apply(VerificationChallenge.pending(
                challenge.id(),
                challenge.purpose(),
                challenge.emailFingerprint(),
                challenge.requesterFingerprint(),
                challenge.otpDigest(),
                challenge.maxAttempts(),
                challenge.expiresAt().plusSeconds(1),
                challenge.resendNotBefore(),
                challenge.createdAt()
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    private static VerificationChallenge pending() {
        return VerificationChallenge.pending(
                UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                bytes(4),
                null,
                bytes(5),
                5,
                NOW.plusSeconds(300),
                NOW.plusSeconds(60),
                NOW
        );
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
