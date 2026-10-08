package com.vkplatform.iam.verification.infrastructure.persistence.repository;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationRepository;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class JpaEmailVerificationRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");

    @Autowired
    private JpaEmailVerificationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM email_verification_challenges");
    }

    @Test
    void verifiesOnceAndRecordsFailedAttemptsTransactionally() {
        byte[] expectedDigest = bytes(7);
        VerificationChallenge challenge = challenge(UUID.randomUUID(), bytes(1), bytes(2), expectedDigest, NOW);
        repository.createPending(challenge, VerificationPolicy.defaults());
        repository.markSent(challenge.id(), NOW.plusSeconds(1));

        EmailVerificationRepository.VerificationAttempt incorrect = repository.verify(
                challenge.id(),
                bytes(8),
                NOW.plusSeconds(2)
        );
        EmailVerificationRepository.VerificationAttempt correct = repository.verify(
                challenge.id(),
                expectedDigest,
                NOW.plusSeconds(3)
        );
        EmailVerificationRepository.VerificationAttempt replay = repository.verify(
                challenge.id(),
                expectedDigest,
                NOW.plusSeconds(4)
        );

        assertThat(incorrect.verified()).isFalse();
        assertThat(correct.verified()).isTrue();
        assertThat(correct.purpose()).isEqualTo(VerificationPurpose.RESTRICTED_RESOURCE_ACCESS);
        assertThat(replay.verified()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM email_verification_challenges WHERE id = ?",
                Integer.class,
                challenge.id()
        )).isEqualTo(1);
    }

    @Test
    void serializesDestinationRateLimitsAndReturnsTheRetryInstant() {
        VerificationPolicy policy = VerificationPolicy.defaults();
        byte[] emailFingerprint = bytes(3);
        VerificationChallenge first = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                bytes(4),
                bytes(5),
                NOW
        );
        repository.createPending(first, policy);

        VerificationChallenge second = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                bytes(6),
                bytes(7),
                NOW.plusSeconds(10)
        );
        assertThatThrownBy(() -> repository.createPending(second, policy))
                .isInstanceOfSatisfying(VerificationFailure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.RATE_LIMITED);
                    assertThat(failure.retryAt()).isEqualTo(NOW.plusSeconds(60));
                });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_verification_challenges",
                Integer.class
        )).isEqualTo(1);
    }

    private static VerificationChallenge challenge(
            UUID id,
            byte[] emailFingerprint,
            byte[] requesterFingerprint,
            byte[] digest,
            Instant createdAt
    ) {
        return new VerificationChallenge(
                id,
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                emailFingerprint,
                requesterFingerprint,
                digest,
                5,
                createdAt.plusSeconds(300),
                createdAt.plusSeconds(60),
                createdAt
        );
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
