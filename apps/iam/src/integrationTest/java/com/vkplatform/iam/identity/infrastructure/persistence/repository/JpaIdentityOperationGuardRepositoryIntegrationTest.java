package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.infrastructure.persistence.entity.IdentityOperationGuardEntity_;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaIdentityOperationGuardRepositoryIntegrationTest {
    @Autowired
    private JpaIdentityOperationGuardRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void theMigrationSeedsSixtyFourGuards() {
        assertThat(IdentityOperationGuardEntity_.id).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.identity_operation_guards",
                Integer.class
        )).isEqualTo(64);
    }

    @Test
    void locksTheGuardChosenByTheFingerprintsFirstByteUntilTheTransactionEnds() throws Exception {
        byte[] fingerprint = new byte[32];
        fingerprint[0] = (byte) 200;

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.lockForIdentifier(fingerprint);
            assertThatThrownBy(() -> lockWithoutWaiting((short) (200 % 64)))
                    .isInstanceOfSatisfying(SQLException.class, failure ->
                            assertThat(failure.getSQLState()).isEqualTo("55P03"));
            lockWithoutWaitingSucceeds((short) (201 % 64));
        });

        lockWithoutWaiting((short) (200 % 64));
    }

    private void lockWithoutWaitingSucceeds(short guardId) {
        try {
            lockWithoutWaiting(guardId);
        } catch (SQLException exception) {
            throw new AssertionError("guard " + guardId + " should not be locked", exception);
        }
    }

    private void lockWithoutWaiting(short guardId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM iam_identity.identity_operation_guards WHERE id = ? FOR UPDATE NOWAIT"
            )) {
                statement.setShort(1, guardId);
                statement.executeQuery().close();
            } finally {
                connection.rollback();
            }
        }
    }
}
