package com.vkplatform.iam.identity.application.service;

import com.vkplatform.iam.identity.api.command.AccountAdministrationCommand;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.command.VerifyPasswordlessSignIn;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.identity.application.port.out.AccountRepository;
import com.vkplatform.iam.identity.application.port.out.GrantTokenCodec;
import com.vkplatform.iam.identity.application.port.out.IdentityOperationGuardRepository;
import com.vkplatform.iam.identity.application.port.out.LoginIdentityRepository;
import com.vkplatform.iam.identity.application.port.out.SecurityEventRepository;
import com.vkplatform.iam.identity.application.port.out.VerificationGrantRepository;
import com.vkplatform.iam.identity.domain.AccountStatus;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.identity.domain.IdentityPolicy;
import com.vkplatform.iam.verification.api.EmailVerificationOperations;
import com.vkplatform.iam.verification.api.command.StartEmailVerification;
import com.vkplatform.iam.verification.api.command.StartSignInEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the sign-in and account flows through the real service and the real per-table
 * repositories on PostgreSQL. Each call runs in its own transaction, as the service's
 * {@code @Transactional} boundary would.
 */
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "iam.email.cleanup-enabled=false"
})
class PasswordlessIdentityFlowIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T04:00:00Z");
    private static final String AUDIENCE = "vk-platform-web";
    private static final String GRANT = "A".repeat(43);

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private LoginIdentityRepository loginIdentities;

    @Autowired
    private VerificationGrantRepository verificationGrants;

    @Autowired
    private SecurityEventRepository securityEvents;

    @Autowired
    private IdentityOperationGuardRepository operationGuards;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
        statistics().clear();
    }

    @Test
    void resolvesOneAccountPerEmailAndIssuesDigestOnlyGrants() {
        UUID first = issue(bytes(3), bytes(4), NOW, "request-1");
        UUID second = issue(bytes(3), bytes(5), NOW.plusSeconds(1), "request-2");

        assertThat(second).isEqualTo(first);
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("login_identities")).isEqualTo(1);
        assertThat(count("verification_grants")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT grant_digest FROM iam_identity.verification_grants WHERE grant_digest = ?",
                byte[].class,
                bytes(4)
        )).containsOnly((byte) 4);
    }

    @Test
    void aFailedWriteRollsBackTheWholeSignIn() {
        assertThatThrownBy(() -> issue(new byte[31], bytes(2), NOW, "request-invalid"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("accounts")).isZero();
        assertThat(count("security_events")).isZero();
    }

    @Test
    void concurrentFirstSignInsCannotCreateDuplicateAccounts() throws Exception {
        List<UUID> accountIds = runConcurrently(
                () -> issue(bytes(6), bytes(7), NOW, "request-a"),
                () -> issue(bytes(6), bytes(8), NOW, "request-b")
        );

        assertThat(accountIds.get(0)).isEqualTo(accountIds.get(1));
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("login_identities")).isEqualTo(1);
        assertThat(count("verification_grants")).isEqualTo(2);
    }

    @Test
    void enforcesGrantAudienceExpiryOneTimeUseAndReplayProtection() {
        issue(bytes(9), bytes(10), NOW, "request-issue");

        assertThatThrownBy(() -> inTransaction(() -> service(bytes(9), bytes(10), NOW.plusSeconds(1))
                .consumeSignInGrant(new ConsumeSignInGrant(GRANT, "wrong-audience", "request-wrong"))))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_AUDIENCE));

        assertThat(consume(bytes(10), NOW.plusSeconds(2), "request-consume")).isTrue();
        assertThat(consume(bytes(10), NOW.plusSeconds(3), "request-replay")).isFalse();

        issue(bytes(11), bytes(12), NOW, "request-expiring");
        assertThat(consume(bytes(12), NOW.plus(Duration.ofMinutes(5)), "request-expired")).isFalse();
    }

    @Test
    void concurrentGrantConsumptionProducesExactlyOneSuccess() throws Exception {
        issue(bytes(13), bytes(14), NOW, "request-issue");

        List<Boolean> results = runConcurrently(
                () -> consume(bytes(14), NOW.plusSeconds(1), "request-first"),
                () -> consume(bytes(14), NOW.plusSeconds(1), "request-second")
        );

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.security_events WHERE event_type = 'sign_in_grant_consumed'",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void accountStateChangesAreAuditedAndDeletionRetainsSafeIdentityHistory() {
        UUID accountId = issue(bytes(15), bytes(16), NOW, "request-create");
        var suspend = new AccountAdministrationCommand(accountId, "admin-service", "request-suspend");

        inTransaction(() -> service(bytes(15), bytes(16), NOW.plusSeconds(10)).suspendAccount(suspend));
        assertThat(accounts.findById(accountId)).hasValueSatisfying(stored -> {
            assertThat(stored.status()).isEqualTo(AccountStatus.SUSPENDED);
            assertThat(stored.tokenNotBefore()).isEqualTo(NOW.plusSeconds(10));
            assertThat(stored.updatedAt()).isEqualTo(NOW.plusSeconds(10));
        });
        assertThat(eventTime("request-suspend")).isEqualTo(NOW.plusSeconds(10));
        assertThatThrownBy(() -> issue(bytes(15), bytes(17), NOW.plusSeconds(11), "request-blocked"))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_UNAVAILABLE));

        inTransaction(() -> service(bytes(15), bytes(16), NOW.plusSeconds(20)).reactivateAccount(
                new AccountAdministrationCommand(accountId, "admin-service", "request-reactivate")
        ));
        assertThat(consume(bytes(16), NOW.plusSeconds(21), "request-consume")).isTrue();

        inTransaction(() -> service(bytes(15), bytes(16), NOW.plusSeconds(30)).deleteAccount(
                new AccountAdministrationCommand(accountId, "admin-service", "request-delete")
        ));
        assertThat(accounts.findById(accountId)).hasValueSatisfying(stored ->
                assertThat(stored.deletedAt()).isEqualTo(NOW.plusSeconds(30)));
        assertThat(count("login_identities")).isEqualTo(1);
        assertThat(count("verification_grants")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT event_type FROM iam_identity.security_events WHERE account_id = ?",
                String.class,
                accountId
        )).contains(
                "account_created",
                "login_identity_linked",
                "sign_in_grant_issued",
                "account_suspended",
                "account_reactivated",
                "sign_in_grant_consumed",
                "account_deleted"
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.security_events WHERE actor_id = 'admin-service'",
                Integer.class
        )).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM iam_identity.accounts WHERE id = ?",
                Long.class,
                accountId
        )).isEqualTo(3L);
    }

    @Test
    void representativeFlowsRemainBounded() {
        statistics().clear();
        issue(bytes(18), bytes(19), NOW, "request-query-count");
        assertThat(statistics().getPrepareStatementCount()).isLessThanOrEqualTo(9);

        statistics().clear();
        consume(bytes(19), NOW.plusSeconds(1), "request-consume-count");
        assertThat(statistics().getPrepareStatementCount()).isLessThanOrEqualTo(5);
    }

    private UUID issue(byte[] fingerprint, byte[] digest, Instant now, String requestId) {
        return inTransaction(() -> service(fingerprint, digest, now).verifySignIn(new VerifyPasswordlessSignIn(
                FixedVerification.CHALLENGE_ID.toString(),
                "123456",
                requestId
        ))).accountId();
    }

    private boolean consume(byte[] digest, Instant now, String requestId) {
        try {
            AuthenticatedAccount account = inTransaction(() -> service(bytes(0), digest, now)
                    .consumeSignInGrant(new ConsumeSignInGrant(GRANT, AUDIENCE, requestId)));
            return account != null;
        } catch (IdentityFailure failure) {
            assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_GRANT);
            return false;
        }
    }

    private PasswordlessIdentityService service(byte[] fingerprint, byte[] digest, Instant now) {
        GrantTokenCodec grants = new GrantTokenCodec() {
            @Override
            public GeneratedGrant generate() {
                return new GeneratedGrant(GRANT, digest.clone());
            }

            @Override
            public byte[] digest(String grant) {
                return digest.clone();
            }
        };
        return new PasswordlessIdentityService(
                new FixedVerification(fingerprint),
                accounts,
                loginIdentities,
                verificationGrants,
                securityEvents,
                operationGuards,
                grants,
                Clock.fixed(now, ZoneOffset.UTC),
                new IdentityPolicy(AUDIENCE, Duration.ofMinutes(5)),
                event -> { }
        );
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM iam_identity." + table, Integer.class);
    }

    private Instant eventTime(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT occurred_at FROM iam_identity.security_events WHERE request_id = ?",
                OffsetDateTime.class,
                requestId
        ).toInstant();
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
            return List.of(firstResult.get(10, TimeUnit.SECONDS), secondResult.get(10, TimeUnit.SECONDS));
        }
    }

    private static <T> Callable<T> awaitStart(Callable<T> operation, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent operation did not start in time");
            }
            return operation.call();
        };
    }

    /** Stands in for the email verification module: every code verifies the given fingerprint. */
    private record FixedVerification(byte[] fingerprint) implements EmailVerificationOperations {
        private static final UUID CHALLENGE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");

        @Override
        public VerificationChallengeStarted start(StartEmailVerification command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VerificationChallengeStarted startSignIn(StartSignInEmailVerification command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmailVerified verify(VerifyEmailCode command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmailVerified verifySignIn(VerifyEmailCode command) {
            return new EmailVerified(CHALLENGE_ID, "sign_in", NOW, fingerprint.clone());
        }
    }
}
