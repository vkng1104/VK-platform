package com.vkplatform.iam.verification.infrastructure.persistence.repository;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationRepository;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity_;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationOperationGuardEntity_;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "iam.email.cleanup-enabled=false"
})
class JpaEmailVerificationRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");

    @Autowired
    private JpaEmailVerificationRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM iam_identity.email_verification_challenges");
        statistics().clear();
    }

    @Test
    void flywaySchemaMatchesTheGeneratedMetamodel() {
        assertThat(EmailVerificationChallengeEntity_.id).isNotNull();
        assertThat(EmailVerificationChallengeEntity_.createdAt).isNotNull();
        assertThat(EmailVerificationOperationGuardEntity_.id).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.email_verification_operation_guards WHERE id = 1",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('iam_identity.email_verification_challenges')::text",
                String.class
        )).isEqualTo("iam_identity.email_verification_challenges");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('iam_identity.email_verification_operation_guards')::text",
                String.class
        )).isEqualTo("iam_identity.email_verification_operation_guards");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.email_verification_challenges')::text",
                String.class
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.email_verification_operation_guards')::text",
                String.class
        )).isNull();

        VerificationChallenge invalid = challenge(
                UUID.randomUUID(),
                bytes(55),
                bytes(56),
                new byte[31],
                NOW
        );
        assertThatThrownBy(() -> repository.createPending(invalid, VerificationPolicy.defaults()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createsPendingAndSupersedesTheEarlierActiveChallenge() {
        VerificationPolicy policy = policy(Duration.ofSeconds(1), 10, 10, 100);
        byte[] emailFingerprint = bytes(1);
        VerificationChallenge first = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                bytes(2),
                bytes(3),
                NOW
        );
        VerificationChallenge second = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                bytes(4),
                bytes(5),
                NOW.plusSeconds(2)
        );

        repository.createPending(first, policy);
        repository.createPending(second, policy);

        assertThat(timestamp("invalidated_at", first.id())).isEqualTo(second.createdAt());
        assertThat(timestamp("invalidated_at", second.id())).isNull();
        assertThat(text("purpose", second.id()))
                .isEqualTo(VerificationPurpose.RESTRICTED_RESOURCE_ACCESS.wireValue());
        assertThat(text("delivery_status", second.id())).isEqualTo("pending");
    }

    @Test
    void appliesDeliveryTransitionsOnlyFromPendingState() {
        VerificationPolicy policy = relaxedPolicy();
        VerificationChallenge sent = challenge(
                UUID.randomUUID(),
                bytes(6),
                bytes(7),
                bytes(8),
                NOW
        );
        VerificationChallenge failed = challenge(
                UUID.randomUUID(),
                bytes(9),
                bytes(10),
                bytes(11),
                NOW.plusSeconds(2)
        );
        repository.createPending(sent, policy);
        repository.createPending(failed, policy);

        repository.markSent(sent.id(), NOW.plusSeconds(3));
        repository.markFailed(failed.id());

        assertThat(text("delivery_status", sent.id())).isEqualTo("sent");
        assertThat(timestamp("sent_at", sent.id())).isEqualTo(NOW.plusSeconds(3));
        assertThat(text("delivery_status", failed.id())).isEqualTo("failed");
        assertThatThrownBy(() -> repository.markSent(sent.id(), NOW.plusSeconds(4)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> repository.markFailed(failed.id()))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void serializesDestinationCooldownAndReturnsTheRetryInstant() {
        VerificationPolicy policy = VerificationPolicy.defaults();
        byte[] emailFingerprint = bytes(12);
        VerificationChallenge first = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                bytes(13),
                bytes(14),
                NOW
        );
        repository.createPending(first, policy);

        VerificationChallenge second = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                bytes(15),
                bytes(16),
                NOW.plusSeconds(10)
        );
        assertRateLimited(second, policy, NOW.plusSeconds(60));

        assertThat(challengeCount()).isEqualTo(1);
    }

    @Test
    void appliesTheBoundedEmailWindowThrottle() {
        byte[] emailFingerprint = bytes(17);
        insertPending(emailFingerprint, null, NOW.minus(Duration.ofMinutes(10)));
        insertPending(emailFingerprint, null, NOW.minus(Duration.ofMinutes(5)));
        VerificationPolicy policy = policy(Duration.ofSeconds(1), 2, 10, 100);
        VerificationChallenge attempted = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                null,
                bytes(18),
                NOW
        );

        assertRateLimited(attempted, policy, NOW.plus(Duration.ofMinutes(5)));
        assertThat(challengeCount()).isEqualTo(2);
    }

    @Test
    void appliesTheBoundedRequesterWindowThrottle() {
        byte[] requesterFingerprint = bytes(19);
        insertPending(bytes(20), requesterFingerprint, NOW.minus(Duration.ofMinutes(50)));
        insertPending(bytes(21), requesterFingerprint, NOW.minus(Duration.ofMinutes(30)));
        VerificationPolicy policy = policy(Duration.ofSeconds(1), 10, 2, 100);
        VerificationChallenge attempted = challenge(
                UUID.randomUUID(),
                bytes(22),
                requesterFingerprint,
                bytes(23),
                NOW
        );

        assertRateLimited(attempted, policy, NOW.plus(Duration.ofMinutes(10)));
        assertThat(challengeCount()).isEqualTo(2);
    }

    @Test
    void appliesTheBoundedGlobalWindowThrottle() {
        insertPending(bytes(24), bytes(25), NOW.minus(Duration.ofHours(23)));
        insertPending(bytes(26), bytes(27), NOW.minus(Duration.ofHours(12)));
        VerificationPolicy policy = policy(Duration.ofSeconds(1), 10, 10, 2);
        VerificationChallenge attempted = challenge(
                UUID.randomUUID(),
                bytes(28),
                bytes(29),
                bytes(30),
                NOW
        );

        assertRateLimited(attempted, policy, NOW.plus(Duration.ofHours(1)));
        assertThat(challengeCount()).isEqualTo(2);
    }

    @Test
    void verifiesOnceAndRecordsFailedAttemptsTransactionally() {
        byte[] expectedDigest = bytes(31);
        VerificationChallenge challenge = challenge(
                UUID.randomUUID(),
                bytes(32),
                bytes(33),
                expectedDigest,
                NOW
        );
        repository.createPending(challenge, VerificationPolicy.defaults());
        repository.markSent(challenge.id(), NOW.plusSeconds(1));

        EmailVerificationRepository.VerificationAttempt incorrect = repository.verify(
                challenge.id(),
                bytes(34),
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
        assertThat(integer("attempt_count", challenge.id())).isEqualTo(1);
    }

    @Test
    void rejectsExpiredAndAttemptExhaustedChallenges() {
        VerificationChallenge expired = challenge(
                UUID.randomUUID(),
                bytes(35),
                bytes(36),
                bytes(37),
                NOW
        );
        VerificationChallenge exhausted = challenge(
                UUID.randomUUID(),
                bytes(38),
                bytes(39),
                bytes(40),
                NOW.plusSeconds(2),
                2
        );
        repository.createPending(expired, relaxedPolicy());
        repository.createPending(exhausted, relaxedPolicy());
        repository.markSent(expired.id(), NOW.plusSeconds(1));
        repository.markSent(exhausted.id(), NOW.plusSeconds(3));

        assertThat(repository.verify(expired.id(), bytes(37), expired.expiresAt()).verified()).isFalse();
        assertThat(repository.verify(exhausted.id(), bytes(41), NOW.plusSeconds(4)).verified()).isFalse();
        assertThat(repository.verify(exhausted.id(), bytes(42), NOW.plusSeconds(5)).verified()).isFalse();
        assertThat(repository.verify(exhausted.id(), bytes(40), NOW.plusSeconds(6)).verified()).isFalse();
        assertThat(integer("attempt_count", exhausted.id())).isEqualTo(2);
    }

    @Test
    void concurrentStartsCannotBypassTheDestinationRateLimit() throws Exception {
        byte[] emailFingerprint = bytes(43);
        VerificationPolicy policy = VerificationPolicy.defaults();
        VerificationChallenge first = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                bytes(44),
                bytes(45),
                NOW
        );
        VerificationChallenge second = challenge(
                UUID.randomUUID(),
                emailFingerprint,
                bytes(46),
                bytes(47),
                NOW
        );

        List<Boolean> results = runConcurrently(
                () -> createPending(first, policy),
                () -> createPending(second, policy)
        );

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(challengeCount()).isEqualTo(1);
    }

    @Test
    void concurrentVerificationProducesExactlyOneSuccess() throws Exception {
        byte[] expectedDigest = bytes(48);
        VerificationChallenge challenge = challenge(
                UUID.randomUUID(),
                bytes(49),
                bytes(50),
                expectedDigest,
                NOW
        );
        repository.createPending(challenge, VerificationPolicy.defaults());
        repository.markSent(challenge.id(), NOW.plusSeconds(1));

        List<Boolean> results = runConcurrently(
                () -> repository.verify(challenge.id(), expectedDigest, NOW.plusSeconds(2)).verified(),
                () -> repository.verify(challenge.id(), expectedDigest, NOW.plusSeconds(2)).verified()
        );

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(timestamp("verified_at", challenge.id())).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void deletesOnlyOneBoundedBatchOfRetainedTerminalChallenges() {
        Instant old = NOW.minus(Duration.ofDays(2));
        VerificationPolicy policy = relaxedPolicy();
        VerificationChallenge expired = challenge(
                UUID.randomUUID(), bytes(57), bytes(58), bytes(59), old
        );
        VerificationChallenge verified = challenge(
                UUID.randomUUID(), bytes(60), bytes(61), bytes(62), old.plusSeconds(1)
        );
        VerificationChallenge failed = challenge(
                UUID.randomUUID(), bytes(63), bytes(64), bytes(65), old.plusSeconds(2)
        );
        VerificationChallenge active = challenge(
                UUID.randomUUID(), bytes(66), bytes(67), bytes(68), NOW
        );

        repository.createPending(expired, policy);
        repository.createPending(verified, policy);
        repository.markSent(verified.id(), old.plusSeconds(2));
        assertThat(repository.verify(verified.id(), verified.otpDigest(), old.plusSeconds(3)).verified()).isTrue();
        repository.createPending(failed, policy);
        repository.markFailed(failed.id());
        repository.createPending(active, policy);

        Instant cutoff = NOW.minus(Duration.ofHours(24));
        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isEqualTo(2);
        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isEqualTo(1);
        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isZero();
        assertThat(challengeIds()).containsExactly(active.id());
    }

    @Test
    void concurrentCleanupWorkersSerializeBoundedBatchesWithoutDeletingActiveRows() throws Exception {
        Instant old = NOW.minus(Duration.ofDays(2));
        VerificationPolicy policy = relaxedPolicy();
        for (int index = 0; index < 6; index++) {
            VerificationChallenge terminal = challenge(
                    UUID.randomUUID(),
                    bytes(70 + index),
                    bytes(80 + index),
                    bytes(90 + index),
                    old.plusSeconds(index)
            );
            repository.createPending(terminal, policy);
            repository.markFailed(terminal.id());
        }
        VerificationChallenge active = challenge(
                UUID.randomUUID(), bytes(100), bytes(101), bytes(102), NOW
        );
        repository.createPending(active, policy);

        Instant cutoff = NOW.minus(Duration.ofHours(24));
        List<Integer> deleted = runConcurrently(
                () -> repository.deleteTerminalBefore(cutoff, 2),
                () -> repository.deleteTerminalBefore(cutoff, 2)
        );

        assertThat(deleted).containsExactlyInAnyOrder(2, 2);
        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isEqualTo(2);
        assertThat(repository.deleteTerminalBefore(cutoff, 2)).isZero();
        assertThat(challengeIds()).containsExactly(active.id());
    }

    @Test
    void representativeOperationsStayWithinTheirQueryBudgets() {
        VerificationChallenge challenge = challenge(
                UUID.randomUUID(),
                bytes(51),
                bytes(52),
                bytes(53),
                NOW
        );

        statistics().clear();
        repository.createPending(challenge, VerificationPolicy.defaults());
        assertThat(statistics().getPrepareStatementCount()).isLessThanOrEqualTo(8);

        repository.markSent(challenge.id(), NOW.plusSeconds(1));
        statistics().clear();
        repository.verify(challenge.id(), bytes(53), NOW.plusSeconds(2));
        assertThat(statistics().getPrepareStatementCount()).isLessThanOrEqualTo(2);

        Instant old = NOW.minus(Duration.ofDays(2));
        VerificationChallenge cleanupCandidate = challenge(
                UUID.randomUUID(), bytes(103), bytes(104), bytes(105), old
        );
        repository.createPending(cleanupCandidate, relaxedPolicy());
        repository.markFailed(cleanupCandidate.id());
        statistics().clear();
        assertThat(repository.deleteTerminalBefore(NOW.minus(Duration.ofHours(24)), 1)).isEqualTo(1);
        assertThat(statistics().getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    private boolean createPending(VerificationChallenge challenge, VerificationPolicy policy) {
        try {
            repository.createPending(challenge, policy);
            return true;
        } catch (VerificationFailure failure) {
            assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.RATE_LIMITED);
            return false;
        }
    }

    private void assertRateLimited(
            VerificationChallenge challenge,
            VerificationPolicy policy,
            Instant expectedRetryAt
    ) {
        assertThatThrownBy(() -> repository.createPending(challenge, policy))
                .isInstanceOfSatisfying(VerificationFailure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.RATE_LIMITED);
                    assertThat(failure.retryAt()).isEqualTo(expectedRetryAt);
                });
    }

    private void insertPending(byte[] emailFingerprint, byte[] requesterFingerprint, Instant createdAt) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO iam_identity.email_verification_challenges (
                        id,
                        purpose,
                        email_fingerprint,
                        requester_fingerprint,
                        otp_digest,
                        attempt_count,
                        max_attempts,
                        expires_at,
                        resend_not_before,
                        delivery_status,
                        created_at
                    ) VALUES (?, ?, ?, ?, ?, 0, 5, ?, ?, 'pending', ?)
                    """);
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, VerificationPurpose.RESTRICTED_RESOURCE_ACCESS.wireValue());
            statement.setBytes(3, emailFingerprint);
            statement.setBytes(4, requesterFingerprint);
            statement.setBytes(5, bytes(54));
            statement.setObject(6, utc(createdAt.plus(Duration.ofMinutes(5))));
            statement.setObject(7, utc(createdAt.plusSeconds(1)));
            statement.setObject(8, utc(createdAt));
            return statement;
        });
    }

    private int challengeCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.email_verification_challenges",
                Integer.class
        );
    }

    private List<UUID> challengeIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM iam_identity.email_verification_challenges ORDER BY id",
                UUID.class
        );
    }

    private Integer integer(String column, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM iam_identity.email_verification_challenges WHERE id = ?",
                Integer.class,
                id
        );
    }

    private String text(String column, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM iam_identity.email_verification_challenges WHERE id = ?",
                String.class,
                id
        );
    }

    private Instant timestamp(String column, UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM iam_identity.email_verification_challenges WHERE id = ?",
                Instant.class,
                id
        );
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static VerificationPolicy relaxedPolicy() {
        return policy(Duration.ofSeconds(1), 10, 10, 100);
    }

    private static VerificationPolicy policy(
            Duration resendCooldown,
            int maxStartsPerEmail,
            int maxStartsPerRequester,
            int maxGlobalStarts
    ) {
        return new VerificationPolicy(
                Duration.ofMinutes(5),
                resendCooldown,
                Duration.ofMinutes(15),
                maxStartsPerEmail,
                Duration.ofHours(1),
                maxStartsPerRequester,
                Duration.ofHours(24),
                maxGlobalStarts,
                5
        );
    }

    private static VerificationChallenge challenge(
            UUID id,
            byte[] emailFingerprint,
            byte[] requesterFingerprint,
            byte[] digest,
            Instant createdAt
    ) {
        return challenge(id, emailFingerprint, requesterFingerprint, digest, createdAt, 5);
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

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static <T> List<T> runConcurrently(Callable<T> first, Callable<T> second) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<T> firstResult = executor.submit(awaitStart(first, ready, start));
            Future<T> secondResult = executor.submit(awaitStart(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS)
            );
        }
    }

    private static <T> Callable<T> awaitStart(
            Callable<T> operation,
            CountDownLatch ready,
            CountDownLatch start
    ) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent operation did not start in time");
            }
            return operation.call();
        };
    }
}
