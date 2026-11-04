package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.application.port.out.PasswordlessIdentityRepository;
import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.AccountStatus;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.AccountEntity_;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.IdentityOperationGuardEntity_;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.LoginIdentityEntity_;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.SecurityEventEntity_;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.VerificationGrantEntity_;
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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "iam.email.cleanup-enabled=false"
})
class JpaPasswordlessIdentityRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T04:00:00Z");
    private static final String AUDIENCE = "vk-platform-web";

    @Autowired
    private JpaPasswordlessIdentityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DataSource dataSource;

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
    void flywaySchemaMatchesGeneratedMappingsAndPrivacyConstraints() {
        assertThat(AccountEntity_.id).isNotNull();
        assertThat(LoginIdentityEntity_.identifierFingerprint).isNotNull();
        assertThat(VerificationGrantEntity_.grantDigest).isNotNull();
        assertThat(SecurityEventEntity_.eventType).isNotNull();
        assertThat(IdentityOperationGuardEntity_.id).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.identity_operation_guards",
                Integer.class
        )).isEqualTo(64);
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'iam_identity'
                  AND table_name = 'login_identities'
                """, String.class)).doesNotContain("email", "raw_email", "encrypted_contact");

        assertThatThrownBy(() -> repository.issueSignInGrant(
                new byte[31],
                bytes(2),
                AUDIENCE,
                NOW.plusSeconds(300),
                NOW,
                "request-invalid"
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("accounts")).isZero();
    }

    @Test
    void resolvesOneAccountAndIssuesDigestOnlyGrants() {
        byte[] fingerprint = bytes(3);
        PasswordlessIdentityRepository.IssuedGrant first = issue(fingerprint, bytes(4), NOW, "request-1");
        PasswordlessIdentityRepository.IssuedGrant second = issue(
                fingerprint,
                bytes(5),
                NOW.plusSeconds(1),
                "request-2"
        );

        assertThat(second.accountId()).isEqualTo(first.accountId());
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
    void concurrentResolutionCannotCreateDuplicateAccounts() throws Exception {
        byte[] fingerprint = bytes(6);

        List<UUID> accountIds = runConcurrently(
                () -> issue(fingerprint, bytes(7), NOW, "request-a").accountId(),
                () -> issue(fingerprint, bytes(8), NOW, "request-b").accountId()
        );

        assertThat(accountIds.get(0)).isEqualTo(accountIds.get(1));
        assertThat(count("accounts")).isEqualTo(1);
        assertThat(count("login_identities")).isEqualTo(1);
        assertThat(count("verification_grants")).isEqualTo(2);
    }

    @Test
    void enforcesGrantAudienceExpiryOneTimeUseAndReplayProtection() {
        issue(bytes(9), bytes(10), NOW, "request-issue");

        assertThatThrownBy(() -> repository.consumeSignInGrant(
                bytes(10),
                "wrong-audience",
                NOW.plusSeconds(1),
                "request-wrong-audience"
        )).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_GRANT));

        Account consumed = repository.consumeSignInGrant(
                bytes(10),
                AUDIENCE,
                NOW.plusSeconds(2),
                "request-consume"
        );
        assertThat(consumed.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThatThrownBy(() -> repository.consumeSignInGrant(
                bytes(10),
                AUDIENCE,
                NOW.plusSeconds(3),
                "request-replay"
        )).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_GRANT));

        issue(bytes(11), bytes(12), NOW, "request-expiring");
        assertThatThrownBy(() -> repository.consumeSignInGrant(
                bytes(12),
                AUDIENCE,
                NOW.plus(Duration.ofMinutes(5)),
                "request-expired"
        )).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_GRANT));
    }

    @Test
    void concurrentGrantConsumptionProducesExactlyOneSuccess() throws Exception {
        issue(bytes(13), bytes(14), NOW, "request-issue");

        List<Boolean> results = runConcurrently(
                () -> consume(bytes(14), "request-first"),
                () -> consume(bytes(14), "request-second")
        );

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.security_events WHERE event_type = 'sign_in_grant_consumed'",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void accountStateChangesAreAuditedAndDeletionRetainsSafeIdentityHistory() {
        UUID accountId = issue(bytes(15), bytes(16), NOW, "request-create").accountId();

        Account suspended = repository.findAccountForStatusChange(accountId).suspend(NOW.plusSeconds(10));
        repository.recordAccountStatusChange(suspended, "admin-service", "request-suspend", NOW.plusSeconds(10));
        Account stored = repository.findAccount(accountId);
        assertThat(stored.status()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(stored.tokenNotBefore()).isEqualTo(NOW.plusSeconds(10));
        assertThat(stored.updatedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(eventTime("request-suspend")).isEqualTo(NOW.plusSeconds(10));
        assertThatThrownBy(() -> issue(
                bytes(15),
                bytes(17),
                NOW.plusSeconds(11),
                "request-blocked"
        )).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_UNAVAILABLE));

        Account reactivated = repository.findAccountForStatusChange(accountId).reactivate(NOW.plusSeconds(20));
        repository.recordAccountStatusChange(reactivated, "admin-service", "request-reactivate", NOW.plusSeconds(20));
        assertThat(repository.consumeSignInGrant(
                bytes(16),
                AUDIENCE,
                NOW.plusSeconds(21),
                "request-consume"
        ).id()).isEqualTo(accountId);

        Account deleted = repository.findAccountForStatusChange(accountId).delete(NOW.plusSeconds(30));
        repository.recordAccountStatusChange(deleted, "admin-service", "request-delete", NOW.plusSeconds(30));
        assertThat(repository.findAccount(accountId).deletedAt()).isEqualTo(NOW.plusSeconds(30));
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
    void statusChangeLookupLocksTheAccountUntilItsTransactionEnds() throws Exception {
        UUID accountId = issue(bytes(20), bytes(21), NOW, "request-lock").accountId();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(repository.findAccountForStatusChange(accountId).id()).isEqualTo(accountId);
            assertThatThrownBy(() -> lockWithoutWaiting(accountId))
                    .isInstanceOfSatisfying(SQLException.class, failure ->
                            assertThat(failure.getSQLState()).isEqualTo("55P03"));
        });

        lockWithoutWaiting(accountId);
        assertThatThrownBy(() -> repository.findAccountForStatusChange(UUID.randomUUID()))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_NOT_FOUND));
    }

    @Test
    void representativeOperationsRemainBounded() {
        statistics().clear();
        issue(bytes(18), bytes(19), NOW, "request-query-count");
        assertThat(statistics().getPrepareStatementCount()).isLessThanOrEqualTo(9);

        statistics().clear();
        repository.consumeSignInGrant(
                bytes(19),
                AUDIENCE,
                NOW.plusSeconds(1),
                "request-consume-count"
        );
        assertThat(statistics().getPrepareStatementCount()).isLessThanOrEqualTo(5);
    }

    private PasswordlessIdentityRepository.IssuedGrant issue(
            byte[] fingerprint,
            byte[] digest,
            Instant now,
            String requestId
    ) {
        return repository.issueSignInGrant(
                fingerprint,
                digest,
                AUDIENCE,
                now.plus(Duration.ofMinutes(5)),
                now,
                requestId
        );
    }

    private boolean consume(byte[] digest, String requestId) {
        try {
            repository.consumeSignInGrant(digest, AUDIENCE, NOW.plusSeconds(1), requestId);
            return true;
        } catch (IdentityFailure failure) {
            assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_GRANT);
            return false;
        }
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity." + table,
                Integer.class
        );
    }

    private Instant eventTime(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT occurred_at FROM iam_identity.security_events WHERE request_id = ?",
                OffsetDateTime.class,
                requestId
        ).toInstant();
    }

    private void lockWithoutWaiting(UUID accountId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM iam_identity.accounts WHERE id = ? FOR UPDATE NOWAIT"
            )) {
                statement.setObject(1, accountId);
                statement.executeQuery().close();
            } finally {
                connection.rollback();
            }
        }
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
