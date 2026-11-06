package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.VerificationGrant;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.VerificationGrantEntity_;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaVerificationGrantRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-06T04:00:00Z");

    @Autowired
    private JpaVerificationGrantRepository repository;

    @Autowired
    private JpaAccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID accountId;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
        Account account = Account.create(UUID.randomUUID(), NOW);
        accounts.create(account);
        accountId = account.id();
    }

    @Test
    void storesOnlyTheDigestAndFindsTheGrantByIt() {
        assertThat(VerificationGrantEntity_.grantDigest).isNotNull();
        VerificationGrant grant = grant();

        repository.create(grant, bytes(3), bytes(4));

        assertThat(repository.findLockedByDigest(bytes(4))).contains(grant);
        assertThat(repository.findLockedByDigest(bytes(5))).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT grant_digest FROM iam_identity.verification_grants WHERE id = ?",
                byte[].class,
                grant.id()
        )).containsOnly((byte) 4);
    }

    @Test
    void locksTheGrantUntilTheTransactionEnds() throws Exception {
        VerificationGrant grant = grant();
        repository.create(grant, bytes(3), bytes(6));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(repository.findLockedByDigest(bytes(6))).contains(grant);
            assertThatThrownBy(() -> lockWithoutWaiting(grant.id()))
                    .isInstanceOfSatisfying(SQLException.class, failure ->
                            assertThat(failure.getSQLState()).isEqualTo("55P03"));
        });

        lockWithoutWaiting(grant.id());
    }

    @Test
    void recordsTheConsumptionTheCallerApplied() {
        VerificationGrant grant = grant();
        repository.create(grant, bytes(3), bytes(7));

        repository.recordConsumption(grant.consume(NOW.plusSeconds(30)));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT consumed_at FROM iam_identity.verification_grants WHERE id = ?",
                OffsetDateTime.class,
                grant.id()
        ).toInstant()).isEqualTo(NOW.plusSeconds(30));
        assertThatThrownBy(() -> repository.recordConsumption(grant().consume(NOW)))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("verification grant does not exist");
    }

    private VerificationGrant grant() {
        return VerificationGrant.issueSignIn(UUID.randomUUID(), accountId, "vk-platform-web", NOW.plusSeconds(300), NOW);
    }

    private void lockWithoutWaiting(UUID grantId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM iam_identity.verification_grants WHERE id = ? FOR UPDATE NOWAIT"
            )) {
                statement.setObject(1, grantId);
                statement.executeQuery().close();
            } finally {
                connection.rollback();
            }
        }
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
