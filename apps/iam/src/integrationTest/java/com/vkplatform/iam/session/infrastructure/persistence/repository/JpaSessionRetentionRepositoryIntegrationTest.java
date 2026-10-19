package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.SessionRetentionRepository.CleanupResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "iam.session.enabled=false",
        "iam.email.cleanup-enabled=false"
})
@Transactional
class JpaSessionRetentionRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-19T04:00:00Z");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JpaSessionRetentionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JpaSessionRetentionRepository(entityManager);
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_client_events");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_clients");
        jdbcTemplate.update("DELETE FROM iam_session.signing_key_events");
        jdbcTemplate.update("DELETE FROM iam_session.signing_keys");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
    }

    @Test
    void cleansDependenciesBeforeParentsAndBoundsEachCleanupStage() {
        UUID accountId = UUID.randomUUID();
        Instant old = NOW.minus(Duration.ofDays(100));
        jdbcTemplate.update("""
                INSERT INTO iam_identity.accounts (
                    id, status, token_not_before, created_at, updated_at
                ) VALUES (?, 'active', ?, ?, ?)
                """, accountId, timestamp(old), timestamp(old), timestamp(old));

        UUID retainedExpired = insertSession(accountId, "active", old, old.plusSeconds(60), null);
        UUID secondExpired = insertSession(accountId, "active", old, old.plusSeconds(120), null);
        UUID staleSession = insertSession(accountId, "revoked", old, old.plusSeconds(180), old.plusSeconds(181));

        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_clients (
                    id, client_id, client_id_issued_at, client_name,
                    client_authentication_methods, authorization_grant_types,
                    redirect_uris, post_logout_redirect_uris, scopes,
                    client_settings, token_settings
                ) VALUES (
                    'retention-client-registration', 'retention-client', ?, 'Retention client',
                    'client_secret_basic', 'authorization_code', '', '', 'api.access', '{}', '{}'
                )
                """, timestamp(old));
        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_authorizations (
                    id, registered_client_id, principal_name, session_id,
                    authorization_grant_type, authorized_scopes, attributes,
                    invalidated_at, invalidation_reason
                ) VALUES (
                    'retention-authorization', 'retention-client-registration', ?, ?,
                    'authorization_code', 'api.access', '{}', ?, 'logout'
                )
                """, accountId.toString(), staleSession, timestamp(old.plusSeconds(182)));
        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_refresh_tokens (
                    id, authorization_id, family_id, token_digest, issued_at, expires_at
                ) VALUES (?, 'retention-authorization', ?, ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), new byte[32], timestamp(old), timestamp(old.plus(Duration.ofDays(1))));
        insertAuditEvent(accountId, old, "request-retention-1");
        insertAuditEvent(accountId, old.plusSeconds(1), "request-retention-2");

        Instant cutoff = NOW.minus(Duration.ofDays(30));
        CleanupResult first = repository.cleanup(NOW, cutoff, cutoff, cutoff, 1);

        assertThat(first).isEqualTo(new CleanupResult(1, 1, 1, 1, 0));
        assertThat(count("iam_session.oauth_refresh_tokens")).isZero();
        assertThat(count("iam_session.oauth_authorizations")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.sessions WHERE id = ?",
                Integer.class,
                staleSession
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                retainedExpired
        )).isEqualTo("revoked");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                secondExpired
        )).isEqualTo("active");
        assertThat(count("iam_identity.security_events")).isEqualTo(1);

        CleanupResult second = repository.cleanup(NOW, cutoff, cutoff, cutoff, 1);
        assertThat(second.expiredSessions()).isEqualTo(1);
        assertThat(second.deletedAuditEvents()).isEqualTo(1);
    }

    private UUID insertSession(
            UUID accountId,
            String status,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO iam_session.sessions (
                    id, account_id, client_id, status, created_at, last_refreshed_at,
                    expires_at, revoked_at, revoke_reason
                ) VALUES (?, ?, 'retention-client', ?, ?, ?, ?, ?, ?)
                """,
                id,
                accountId,
                status,
                timestamp(createdAt),
                timestamp(createdAt),
                timestamp(expiresAt),
                revokedAt == null ? null : timestamp(revokedAt),
                revokedAt == null ? null : "logout"
        );
        return id;
    }

    private void insertAuditEvent(UUID accountId, Instant occurredAt, String requestId) {
        jdbcTemplate.update("""
                INSERT INTO iam_identity.security_events (
                    id, event_type, account_id, target_id, actor_id, request_id, occurred_at
                ) VALUES (?, 'session_revoked', ?, ?, 'retention-test', ?, ?)
                """, UUID.randomUUID(), accountId, accountId, requestId, timestamp(occurredAt));
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
