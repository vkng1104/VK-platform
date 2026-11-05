package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.AccountStatus;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.AccountEntity_;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaAccountRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-06T04:00:00Z");

    @Autowired
    private JpaAccountRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    }

    @Test
    void createsAndReadsAnAccount() {
        assertThat(AccountEntity_.id).isNotNull();
        Account account = Account.create(UUID.randomUUID(), NOW);

        repository.create(account);

        assertThat(repository.findById(account.id())).contains(account);
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void locksAnAccountUntilTheTransactionEndsAndIsEmptyForAMissingOne() throws Exception {
        Account account = Account.create(UUID.randomUUID(), NOW);
        repository.create(account);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(repository.findLockedById(account.id())).contains(account);
            assertThatThrownBy(() -> lockWithoutWaiting(account.id()))
                    .isInstanceOfSatisfying(SQLException.class, failure ->
                            assertThat(failure.getSQLState()).isEqualTo("55P03"));
        });

        lockWithoutWaiting(account.id());
        assertThat(repository.findLockedById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void recordsAStatusChangeTheCallerApplied() {
        Account account = Account.create(UUID.randomUUID(), NOW);
        repository.create(account);

        repository.recordStatusChange(account.suspend(NOW.plusSeconds(10)));

        Account stored = repository.findById(account.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(stored.tokenNotBefore()).isEqualTo(NOW.plusSeconds(10));
        assertThat(stored.updatedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM iam_identity.accounts WHERE id = ?",
                Long.class,
                account.id()
        )).isEqualTo(1L);
        assertThatThrownBy(() -> repository.recordStatusChange(
                Account.create(UUID.randomUUID(), NOW).suspend(NOW.plusSeconds(10))
        )).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_NOT_FOUND));
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
}
