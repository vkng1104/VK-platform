package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.domain.OAuthRefreshToken;
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
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaOAuthRefreshTokenRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-05T05:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("8d6f4a5b-0e1c-4f2a-9b3c-4d5e6f7a8b93");
    private static final UUID SESSION_ID = UUID.fromString("9e7a5b6c-1f2d-4a3b-8c4d-5e6f7a8b9ca4");
    private static final String AUTHORIZATION_ID = "authorization-refresh-ledger";

    @Autowired
    private JpaOAuthRefreshTokenRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
        jdbcTemplate.update("""
                INSERT INTO iam_identity.accounts (
                    id, status, token_not_before, version, created_at, updated_at
                ) VALUES (?, 'active', ?, 0, ?, ?)
                """,
                ACCOUNT_ID,
                timestamp(NOW),
                timestamp(NOW),
                timestamp(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO iam_session.sessions (
                    id, account_id, client_id, status, created_at, last_refreshed_at, expires_at
                ) VALUES (?, ?, 'vk-platform-bff', 'active', ?, ?, ?)
                """,
                SESSION_ID,
                ACCOUNT_ID,
                timestamp(NOW),
                timestamp(NOW),
                timestamp(NOW.plusSeconds(3600))
        );
        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_authorizations (
                    id, registered_client_id, principal_name, session_id,
                    authorization_grant_type, authorized_scopes, attributes_json
                ) VALUES (?, 'bff:vk-platform-bff', ?, ?, 'authorization_code', 'api.access', '{}')
                """,
                AUTHORIZATION_ID,
                ACCOUNT_ID.toString(),
                SESSION_ID
        );
    }

    @Test
    void createsAnEntryAndFindsItAndItsAuthorizationByDigest() {
        OAuthRefreshToken root = root();

        repository.create(root, digest(1));

        assertThat(repository.findAuthorizationIdByTokenDigest(digest(1))).contains(AUTHORIZATION_ID);
        assertThat(repository.findLockedByTokenDigest(digest(1))).contains(root);
        assertThat(repository.findAuthorizationIdByTokenDigest(digest(9))).isEmpty();
        assertThat(repository.findLockedByTokenDigest(digest(9))).isEmpty();
    }

    @Test
    void locksTheEntryUntilTheTransactionEnds() throws Exception {
        OAuthRefreshToken root = root();
        repository.create(root, digest(1));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(repository.findLockedByTokenDigest(digest(1))).contains(root);
            assertThatThrownBy(() -> lockWithoutWaiting(root.id()))
                    .isInstanceOfSatisfying(SQLException.class, failure ->
                            assertThat(failure.getSQLState()).isEqualTo("55P03"));
        });

        lockWithoutWaiting(root.id());
    }

    @Test
    void storesAConsumptionAndItsReplacementWrittenInRotationOrder() {
        OAuthRefreshToken root = root();
        repository.create(root, digest(1));
        UUID nextId = UUID.randomUUID();
        OAuthRefreshToken next = OAuthRefreshToken.issue(
                nextId,
                AUTHORIZATION_ID,
                root.familyId(),
                root.id(),
                NOW.plusSeconds(10),
                NOW.plusSeconds(3600)
        );

        // The service consumes first and inserts the replacement second. The replacement foreign
        // key is checked immediately, so this proves the write order commits.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.recordConsumption(repository.findLockedByTokenDigest(digest(1)).orElseThrow()
                    .consume(nextId, NOW.plusSeconds(10)));
            repository.create(next, digest(2));
        });

        assertThat(row(root.id()))
                .containsEntry("replacement_id", nextId)
                .containsEntry("reuse_detected_at", null);
        assertThat(repository.findLockedByTokenDigest(digest(1)).orElseThrow().consumedAt())
                .isEqualTo(NOW.plusSeconds(10));
        assertThat(row(nextId))
                .containsEntry("family_id", root.familyId())
                .containsEntry("parent_id", root.id())
                .containsEntry("consumed_at", null);
    }

    @Test
    void storesAReuseDetection() {
        OAuthRefreshToken root = root();
        repository.create(root, digest(1));
        UUID nextId = UUID.randomUUID();
        repository.create(OAuthRefreshToken.issue(
                nextId,
                AUTHORIZATION_ID,
                root.familyId(),
                root.id(),
                NOW.plusSeconds(10),
                NOW.plusSeconds(3600)
        ), digest(2));
        OAuthRefreshToken consumed = root.consume(nextId, NOW.plusSeconds(10));
        repository.recordConsumption(consumed);

        repository.recordReuse(consumed.detectReuse(NOW.plusSeconds(20)));

        assertThat(repository.findLockedByTokenDigest(digest(1)).orElseThrow().reuseDetectedAt())
                .isEqualTo(NOW.plusSeconds(20));
        assertThatThrownBy(() -> repository.recordReuse(OAuthRefreshToken.issue(
                UUID.randomUUID(),
                AUTHORIZATION_ID,
                root.familyId(),
                null,
                NOW,
                NOW.plusSeconds(3600)
        ))).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("refresh token does not exist");
    }

    private static OAuthRefreshToken root() {
        UUID id = UUID.randomUUID();
        return OAuthRefreshToken.issue(id, AUTHORIZATION_ID, id, null, NOW, NOW.plusSeconds(3600));
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap("""
                SELECT family_id, parent_id, replacement_id, consumed_at, reuse_detected_at
                FROM iam_session.oauth_refresh_tokens
                WHERE id = ?
                """, id);
    }

    private void lockWithoutWaiting(UUID id) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM iam_session.oauth_refresh_tokens WHERE id = ? FOR UPDATE NOWAIT"
            )) {
                statement.setObject(1, id);
                statement.executeQuery().close();
            } finally {
                connection.rollback();
            }
        }
    }

    private static byte[] digest(int seed) {
        byte[] digest = new byte[32];
        digest[0] = (byte) seed;
        return digest;
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
