package com.vkplatform.iam.verification.application.service;

import com.vkplatform.iam.verification.api.command.StartEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationChallengeRepository;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationOperationGuardRepository;
import com.vkplatform.iam.verification.application.port.out.VerificationEmailSender;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationCode;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import com.vkplatform.iam.verification.infrastructure.crypto.HmacVerificationCodeHasher;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/**
 * Runs the verification services against the real repositories and PostgreSQL to prove their
 * transaction boundaries: the operation guard serializes starts and cleanup batches, the
 * challenge lock serializes code attempts, and each flow stays within its statement budget.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "iam.email.cleanup-enabled=false"
})
class EmailVerificationTransactionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-08T03:00:00Z");
    private static final String EMAIL = "reader@example.com";
    private static final String REQUESTER = "192.0.2.15";
    private static final String CODE = "123456";

    @Autowired
    private EmailVerificationChallengeRepository challenges;

    @Autowired
    private EmailVerificationOperationGuardRepository operationGuards;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    void concurrentStartsCannotBypassTheDestinationRateLimit() throws Exception {
        EmailVerificationService service = service((recipient, code, expiresAt) -> {
        });

        List<Boolean> results = runConcurrently(
                () -> started(service),
                () -> started(service)
        );

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(challengeCount()).isEqualTo(1);
    }

    @Test
    void aRejectedStartLeavesTheActiveChallengeUntouched() {
        EmailVerificationService service = service((recipient, code, expiresAt) -> {
        });
        VerificationChallengeStarted first = service.start(start());

        assertThatThrownBy(() -> service.start(start()))
                .isInstanceOfSatisfying(VerificationFailure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.RATE_LIMITED);
                    assertThat(failure.retryAt()).isEqualTo(NOW.plusSeconds(60));
                });

        assertThat(challengeCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT invalidated_at FROM iam_identity.email_verification_challenges WHERE id = ?",
                Instant.class,
                first.id()
        )).isNull();
    }

    @Test
    void aWrongCodeStaysRecordedAfterTheVerificationIsRejected() {
        EmailVerificationService service = service((recipient, code, expiresAt) -> {
        });
        UUID id = service.start(start()).id();

        assertThatThrownBy(() -> service.verify(new VerifyEmailCode(id.toString(), "654321")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_OR_EXPIRED_CODE));
        assertThat(attemptCount(id)).isEqualTo(1);

        assertThat(service.verify(new VerifyEmailCode(id.toString(), CODE)).verifiedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> service.verify(new VerifyEmailCode(id.toString(), CODE)))
                .isInstanceOf(VerificationFailure.class);
        assertThat(attemptCount(id)).isEqualTo(1);
    }

    @Test
    void concurrentVerificationProducesExactlyOneSuccess() throws Exception {
        EmailVerificationService service = service((recipient, code, expiresAt) -> {
        });
        UUID id = service.start(start()).id();

        List<Boolean> results = runConcurrently(
                () -> verified(service, id),
                () -> verified(service, id)
        );

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT verified_at FROM iam_identity.email_verification_challenges WHERE id = ?",
                Instant.class,
                id
        )).isEqualTo(NOW);
    }

    @Test
    void concurrentCleanupWorkersSerializeBoundedBatchesWithoutDeletingActiveRows() throws Exception {
        Instant old = NOW.minus(Duration.ofDays(2));
        for (int index = 0; index < 6; index++) {
            VerificationChallenge terminal = challenge(70 + index, old.plusSeconds(index));
            challenges.create(terminal);
            challenges.markFailed(terminal.id());
        }
        VerificationChallenge active = challenge(100, NOW);
        challenges.create(active);
        EmailVerificationCleanupService cleanup = cleanup(2);

        List<Integer> deleted = runConcurrently(cleanup::cleanupOnce, cleanup::cleanupOnce);

        assertThat(deleted).containsExactlyInAnyOrder(2, 2);
        assertThat(cleanup.cleanupOnce()).isEqualTo(2);
        assertThat(cleanup.cleanupOnce()).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM iam_identity.email_verification_challenges",
                UUID.class
        )).containsExactly(active.id());
    }

    @Test
    void representativeOperationsStayWithinTheirQueryBudgets() {
        List<Long> statementsBeforeDelivery = new ArrayList<>();
        EmailVerificationService service = service((recipient, code, expiresAt) ->
                statementsBeforeDelivery.add(statistics().getPrepareStatementCount()));

        statistics().clear();
        UUID id = service.start(start()).id();
        assertThat(statementsBeforeDelivery).singleElement()
                .satisfies(registration -> assertThat(registration).isLessThanOrEqualTo(8));

        statistics().clear();
        service.verify(new VerifyEmailCode(id.toString(), CODE));
        assertThat(statistics().getPrepareStatementCount()).isLessThanOrEqualTo(2);

        VerificationChallenge cleanupCandidate = challenge(105, NOW.minus(Duration.ofDays(2)));
        challenges.create(cleanupCandidate);
        challenges.markFailed(cleanupCandidate.id());
        statistics().clear();
        assertThat(cleanup(1).cleanupOnce()).isEqualTo(1);
        assertThat(statistics().getPrepareStatementCount()).isLessThanOrEqualTo(3);
    }

    private EmailVerificationService service(VerificationEmailSender sender) {
        return new EmailVerificationService(
                challenges,
                operationGuards,
                new TransactionTemplate(transactionManager),
                sender,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new VerificationCode(CODE),
                new HmacVerificationCodeHasher(
                        "an-otp-pepper-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),
                        "a-fingerprint-key-with-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)
                ),
                VerificationPolicy.defaults()
        );
    }

    private EmailVerificationCleanupService cleanup(int batchSize) {
        return new EmailVerificationCleanupService(
                challenges,
                operationGuards,
                new TransactionTemplate(transactionManager),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofHours(24),
                batchSize
        );
    }

    private static boolean started(EmailVerificationService service) {
        try {
            service.start(start());
            return true;
        } catch (VerificationFailure failure) {
            assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.RATE_LIMITED);
            return false;
        }
    }

    private static boolean verified(EmailVerificationService service, UUID id) {
        try {
            service.verify(new VerifyEmailCode(id.toString(), CODE));
            return true;
        } catch (VerificationFailure failure) {
            assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_OR_EXPIRED_CODE);
            return false;
        }
    }

    private static StartEmailVerification start() {
        return new StartEmailVerification(EMAIL, "restricted_resource_access", REQUESTER);
    }

    private static VerificationChallenge challenge(int seed, Instant createdAt) {
        return VerificationChallenge.pending(
                UUID.randomUUID(),
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                bytes(seed),
                bytes(seed + 1),
                bytes(seed + 2),
                5,
                createdAt.plusSeconds(300),
                createdAt.plusSeconds(60),
                createdAt
        );
    }

    private int challengeCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.email_verification_challenges",
                Integer.class
        );
    }

    private int attemptCount(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM iam_identity.email_verification_challenges WHERE id = ?",
                Integer.class,
                id
        );
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
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
