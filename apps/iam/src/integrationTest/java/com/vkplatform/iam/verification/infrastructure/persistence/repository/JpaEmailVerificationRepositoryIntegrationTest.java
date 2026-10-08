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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaEmailVerificationRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-09T03:00:00Z");

    @Autowired
    private JpaEmailVerificationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM iam_identity.email_verification_challenges");
    }

    @Test
    void migrationPlacesTheConstrainedTableInTheIdentitySchema() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('iam_identity.email_verification_challenges')::text",
                String.class
        )).isEqualTo("iam_identity.email_verification_challenges");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.email_verification_challenges')::text",
                String.class
        )).isNull();

        VerificationChallenge invalid = challenge(
                UUID.randomUUID(),
                1,
                2,
                new byte[31],
                NOW,
                5
        );
        assertThatThrownBy(() -> repository.createPending(invalid, VerificationPolicy.defaults()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void verifiesOnceAndRecordsFailedAttemptsTransactionally() {
        byte[] expectedDigest = bytes(7);
        VerificationChallenge challenge = challenge(UUID.randomUUID(), 1, 2, expectedDigest, NOW, 5);
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
        assertThat(attemptCount(challenge.id())).isEqualTo(1);
    }

    @Test
    void aRowLockAllowsOnlyOneConcurrentVerificationSuccess() throws Exception {
        byte[] expectedDigest = bytes(9);
        VerificationChallenge challenge = challenge(UUID.randomUUID(), 3, 4, expectedDigest, NOW, 5);
        repository.createPending(challenge, VerificationPolicy.defaults());
        repository.markSent(challenge.id(), NOW.plusSeconds(1));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<EmailVerificationRepository.VerificationAttempt> first = executor.submit(
                    () -> verifyAfterBarrier(ready, start, challenge.id(), expectedDigest)
            );
            Future<EmailVerificationRepository.VerificationAttempt> second = executor.submit(
                    () -> verifyAfterBarrier(ready, start, challenge.id(), expectedDigest)
            );

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long successes = List.of(
                            first.get(5, TimeUnit.SECONDS),
                            second.get(5, TimeUnit.SECONDS)
                    ).stream()
                    .filter(EmailVerificationRepository.VerificationAttempt::verified)
                    .count();

            assertThat(successes).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT verified_at IS NOT NULL FROM iam_identity.email_verification_challenges WHERE id = ?",
                    Boolean.class,
                    challenge.id()
            )).isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsExpiredAndAttemptExhaustedChallenges() {
        VerificationChallenge expired = challenge(UUID.randomUUID(), 5, 6, bytes(10), NOW, 5);
        repository.createPending(expired, VerificationPolicy.defaults());
        repository.markSent(expired.id(), NOW.plusSeconds(1));

        assertThat(repository.verify(expired.id(), bytes(10), expired.expiresAt()).verified()).isFalse();

        VerificationChallenge exhausted = challenge(UUID.randomUUID(), 7, 8, bytes(11), NOW, 2);
        repository.createPending(exhausted, VerificationPolicy.defaults());
        repository.markSent(exhausted.id(), NOW.plusSeconds(1));

        assertThat(repository.verify(exhausted.id(), bytes(12), NOW.plusSeconds(2)).verified()).isFalse();
        assertThat(repository.verify(exhausted.id(), bytes(12), NOW.plusSeconds(3)).verified()).isFalse();
        assertThat(repository.verify(exhausted.id(), bytes(11), NOW.plusSeconds(4)).verified()).isFalse();
        assertThat(attemptCount(exhausted.id())).isEqualTo(2);
    }

    @Test
    void aLaterChallengeSupersedesTheEarlierChallengeForTheSamePurpose() {
        byte[] emailFingerprint = bytes(13);
        VerificationChallenge first = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                bytes(14),
                bytes(15),
                NOW,
                5
        );
        VerificationChallenge second = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                bytes(16),
                bytes(17),
                NOW.plusSeconds(61),
                5
        );

        repository.createPending(first, VerificationPolicy.defaults());
        repository.createPending(second, VerificationPolicy.defaults());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT invalidated_at FROM iam_identity.email_verification_challenges WHERE id = ?",
                Instant.class,
                first.id()
        )).isEqualTo(second.createdAt());
        assertThat(repository.verify(first.id(), first.otpDigest(), NOW.plusSeconds(62)).verified()).isFalse();
    }

    @Test
    void enforcesDestinationRequesterAndGlobalStartLimits() {
        VerificationPolicy policy = new VerificationPolicy(
                Duration.ofMinutes(5),
                Duration.ofSeconds(1),
                Duration.ofMinutes(15),
                2,
                Duration.ofHours(1),
                2,
                Duration.ofHours(24),
                4,
                5
        );

        repository.createPending(challenge(UUID.randomUUID(), 20, 30, bytes(40), NOW, 5), policy);
        repository.createPending(challenge(UUID.randomUUID(), 20, 31, bytes(41), NOW.plusSeconds(2), 5), policy);
        assertRateLimitedAt(
                challenge(UUID.randomUUID(), 20, 32, bytes(42), NOW.plusSeconds(4), 5),
                policy,
                NOW.plus(Duration.ofMinutes(15))
        );

        cleanDatabase();
        repository.createPending(challenge(UUID.randomUUID(), 21, 33, bytes(43), NOW, 5), policy);
        repository.createPending(challenge(UUID.randomUUID(), 22, 33, bytes(44), NOW.plusSeconds(2), 5), policy);
        assertRateLimitedAt(
                challenge(UUID.randomUUID(), 23, 33, bytes(45), NOW.plusSeconds(4), 5),
                policy,
                NOW.plus(Duration.ofHours(1))
        );

        cleanDatabase();
        for (int index = 0; index < 4; index++) {
            repository.createPending(challenge(
                    UUID.randomUUID(),
                    50 + index,
                    60 + index,
                    bytes(70 + index),
                    NOW.plusSeconds(index * 2L),
                    5
            ), policy);
        }
        assertRateLimitedAt(
                challenge(UUID.randomUUID(), 80, 90, bytes(100), NOW.plusSeconds(10), 5),
                policy,
                NOW.plus(Duration.ofHours(24))
        );
    }

    @Test
    void deletesOnlyOneBoundedBatchOfRetainedTerminalChallenges() {
        Instant old = NOW.minus(Duration.ofDays(2));
        VerificationPolicy policy = VerificationPolicy.defaults();
        VerificationChallenge expired = challenge(UUID.randomUUID(), 101, 102, bytes(103), old, 5);
        VerificationChallenge verified = challenge(UUID.randomUUID(), 104, 105, bytes(106), old, 5);
        VerificationChallenge failed = challenge(UUID.randomUUID(), 107, 108, bytes(109), old, 5);
        VerificationChallenge active = challenge(UUID.randomUUID(), 110, 111, bytes(112), NOW, 5);

        repository.createPending(expired, policy);
        repository.createPending(verified, policy);
        repository.markSent(verified.id(), old.plusSeconds(1));
        assertThat(repository.verify(verified.id(), verified.otpDigest(), old.plusSeconds(2)).verified()).isTrue();
        repository.createPending(failed, policy);
        repository.markFailed(failed.id());
        repository.createPending(active, policy);

        Instant cutoff = NOW.minus(Duration.ofHours(24));
        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isEqualTo(2);
        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isEqualTo(1);
        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM iam_identity.email_verification_challenges",
                UUID.class
        )).containsExactly(active.id());
    }

    private EmailVerificationRepository.VerificationAttempt verifyAfterBarrier(
            CountDownLatch ready,
            CountDownLatch start,
            UUID id,
            byte[] digest
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("verification barrier timed out");
        }
        return repository.verify(id, digest, NOW.plusSeconds(2));
    }

    private void assertRateLimitedAt(
            VerificationChallenge challenge,
            VerificationPolicy policy,
            Instant retryAt
    ) {
        assertThatThrownBy(() -> repository.createPending(challenge, policy))
                .isInstanceOfSatisfying(VerificationFailure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.RATE_LIMITED);
                    assertThat(failure.retryAt()).isEqualTo(retryAt);
                });
    }

    private int attemptCount(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM iam_identity.email_verification_challenges WHERE id = ?",
                Integer.class,
                id
        );
    }

    private static VerificationChallenge challenge(
            UUID id,
            int emailFingerprint,
            int requesterFingerprint,
            byte[] digest,
            Instant createdAt,
            int maxAttempts
    ) {
        return challenge(
                id,
                bytes(emailFingerprint),
                bytes(requesterFingerprint),
                digest,
                createdAt,
                maxAttempts
        );
    }

    private static VerificationChallenge challenge(
            UUID id,
            byte[] emailFingerprint,
            byte[] requesterFingerprint,
            byte[] digest,
            Instant createdAt,
            int maxAttempts
    ) {
        return new VerificationChallenge(
                id,
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                emailFingerprint,
                requesterFingerprint,
                digest,
                maxAttempts,
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
