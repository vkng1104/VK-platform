package com.vkplatform.iam.verification.infrastructure.persistence.repository;

import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationOperationGuardEntity_;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaEmailVerificationOperationGuardRepositoryIntegrationTest {
    @Autowired
    private JpaEmailVerificationOperationGuardRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void flywaySeedsTheSingleGuardRow() {
        assertThat(EmailVerificationOperationGuardEntity_.id).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('iam_identity.email_verification_operation_guards')::text",
                String.class
        )).isEqualTo("iam_identity.email_verification_operation_guards");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.email_verification_operation_guards')::text",
                String.class
        )).isNull();
        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM iam_identity.email_verification_operation_guards",
                Integer.class
        )).containsExactly(1);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO iam_identity.email_verification_operation_guards (id) VALUES (2)"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void locksTheGuardUntilTheTransactionEnds() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.lock();
            assertThatThrownBy(this::lockWithoutWaiting)
                    .isInstanceOfSatisfying(SQLException.class, failure ->
                            assertThat(failure.getSQLState()).isEqualTo("55P03"));
        });

        lockWithoutWaiting();
    }

    @Test
    void requiresTheCallersTransaction() {
        assertThatThrownBy(repository::lock).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void reportsAMissingGuardRow() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM iam_identity.email_verification_operation_guards");
            assertThatThrownBy(repository::lock)
                    .isInstanceOf(InvalidDataAccessApiUsageException.class)
                    .hasMessageContaining("email verification operation guard is missing");
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.email_verification_operation_guards",
                Integer.class
        )).isEqualTo(1);
    }

    private void lockWithoutWaiting() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM iam_identity.email_verification_operation_guards WHERE id = 1 FOR UPDATE NOWAIT"
            )) {
                statement.executeQuery().close();
            } finally {
                connection.rollback();
            }
        }
    }
}
