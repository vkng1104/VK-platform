package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.SecurityAuditEventType;
import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.identity.api.command.RecordSecurityAuditEvent;
import com.vkplatform.iam.session.api.IamMaintenanceOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.IamRetentionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = {
        "iam.session.enabled=true",
        "iam.session.bff-client-secret=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "iam.session.bff-origin=https://web.example.test",
        "iam.email.provider=disabled",
        "iam.email.cleanup-enabled=false",
        "iam.operations.cleanup-enabled=false",
        "iam.operations.session-retention=PT1H",
        "iam.operations.authorization-retention=PT1H",
        "iam.operations.audit-retention=PT1H",
        "iam.operations.signing-key-retention=PT1H",
        "iam.operations.cleanup-batch-size=2"
})
@Import(JpaIamRetentionRepositoryIntegrationTest.TestClockConfiguration.class)
class JpaIamRetentionRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-30T04:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("32fa1a0e-a1c9-43a9-8841-89a20aa69ab7");

    @Autowired
    private IamMaintenanceOperations maintenance;

    @Autowired
    private SecurityAuditOperations audit;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_client_events");
        jdbcTemplate.update("DELETE FROM iam_session.signing_key_events");
        jdbcTemplate.update("DELETE FROM iam_session.operator_operations");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_clients");
        jdbcTemplate.update("DELETE FROM iam_session.signing_keys");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
        clock.set(NOW);
        insertAccount();
        insertBffClient();
    }

    @Test
    void expiresInvalidatesAndDeletesInReferentiallySafeRestartableOrder() {
        UUID expiredSession = insertSession("active", NOW.minusSeconds(7200), NOW.minusSeconds(10), null);
        insertAuthorization("expired-active-auth", expiredSession, null);

        UUID oldSession = insertSession(
                "revoked",
                NOW.minusSeconds(10800),
                NOW.minusSeconds(7200),
                NOW.minusSeconds(7200)
        );
        insertAuthorization("old-auth", oldSession, NOW.minusSeconds(7200));

        UUID refreshSession = insertSession(
                "revoked",
                NOW.minusSeconds(10800),
                NOW.minusSeconds(7100),
                NOW.minusSeconds(7100)
        );
        insertAuthorization("old-refresh-auth", refreshSession, NOW.minusSeconds(7100));
        insertRefreshToken("old-refresh-auth", NOW.minusSeconds(7000));

        UUID boundarySession = insertSession(
                "revoked",
                NOW.minusSeconds(7200),
                NOW.minusSeconds(3600),
                NOW.minusSeconds(3600)
        );
        insertAuthorization("boundary-auth", boundarySession, NOW.minusSeconds(3600));

        insertOldLifecycleAudit();

        IamRetentionResult result = maintenance.cleanup(context("cleanup-complete"));

        assertThat(result).isEqualTo(new IamRetentionResult(1, 1, 1, 2, 2, 3, 1));
        assertThat(maintenance.cleanup(context("cleanup-complete"))).isEqualTo(result);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                expiredSession
        )).isEqualTo("revoked");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT invalidation_reason FROM iam_session.oauth_authorizations WHERE id = ?",
                String.class,
                "expired-active-auth"
        )).isEqualTo("expired");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.oauth_authorizations WHERE id IN ('old-auth', 'old-refresh-auth')",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.sessions WHERE id IN (?, ?)",
                Integer.class,
                oldSession,
                refreshSession
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.oauth_authorizations WHERE id = 'boundary-auth'",
                Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT result_details FROM iam_session.operator_operations WHERE operation_id = 'cleanup-complete'",
                String.class
        )).isEqualTo(result.storedValue());
    }

    @Test
    void distinctWorkersUseSkipLockedBatchesWithoutDoubleTransitioningRows() throws Exception {
        for (int index = 0; index < 4; index++) {
            insertSession(
                    "active",
                    NOW.minusSeconds(7200 + index),
                    NOW.minusSeconds(10 + index),
                    null
            );
        }

        CompletableFuture<IamRetentionResult> first = CompletableFuture.supplyAsync(() ->
                maintenance.cleanup(context("cleanup-worker-1"))
        );
        CompletableFuture<IamRetentionResult> second = CompletableFuture.supplyAsync(() ->
                maintenance.cleanup(context("cleanup-worker-2"))
        );
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        assertThat(first.get().expiredSessions() + second.get().expiredSessions()).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_session.sessions
                WHERE revoke_reason = 'expired'
                """, Integer.class)).isEqualTo(4);
    }

    @Test
    @Transactional
    void v12IndexesSupportTheCleanupCandidatePlans() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname IN ('iam_session', 'iam_identity')
                """, String.class)).contains(
                "sessions_active_expiry_cleanup_idx",
                "sessions_terminal_cleanup_idx",
                "oauth_authorizations_invalidation_cleanup_idx",
                "oauth_authorizations_sessionless_expiry_idx",
                "security_events_cleanup_idx",
                "signing_key_events_cleanup_idx",
                "oauth_client_events_cleanup_idx",
                "signing_keys_terminal_cleanup_idx"
        );
        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        List<String> plan = jdbcTemplate.queryForList("""
                EXPLAIN (COSTS OFF)
                SELECT id
                FROM iam_session.sessions
                WHERE status = 'active' AND expires_at <= TIMESTAMPTZ '2026-10-30 04:00:00Z'
                ORDER BY expires_at, id
                LIMIT 2
                """, String.class);
        assertThat(String.join("\n", plan)).contains("sessions_active_expiry_cleanup_idx");

        List<String> terminalSessionPlan = jdbcTemplate.queryForList("""
                EXPLAIN (COSTS OFF)
                SELECT id
                FROM iam_session.sessions
                WHERE status <> 'active'
                  AND COALESCE(compromised_at, revoked_at) < TIMESTAMPTZ '2026-10-30 04:00:00Z'
                ORDER BY COALESCE(compromised_at, revoked_at), id
                LIMIT 2
                """, String.class);
        assertThat(String.join("\n", terminalSessionPlan))
                .contains("sessions_terminal_cleanup_idx");

        List<String> signingKeyPlan = jdbcTemplate.queryForList("""
                EXPLAIN (COSTS OFF)
                SELECT id
                FROM iam_session.signing_keys
                WHERE status IN ('RETIRED', 'COMPROMISED')
                  AND COALESCE(retired_at, compromised_at) < TIMESTAMPTZ '2026-10-30 04:00:00Z'
                ORDER BY COALESCE(retired_at, compromised_at), id
                LIMIT 2
                """, String.class);
        assertThat(String.join("\n", signingKeyPlan))
                .contains("signing_keys_terminal_cleanup_idx");
    }

    private void insertOldLifecycleAudit() {
        Instant old = NOW.minusSeconds(7200);
        audit.record(new RecordSecurityAuditEvent(
                SecurityAuditEventType.SESSION_REVOKED,
                ACCOUNT_ID,
                UUID.randomUUID(),
                ACCOUNT_ID.toString(),
                "old-security-event",
                old
        ));
        byte[] keyMaterial = new byte[384];
        Arrays.fill(keyMaterial, (byte) 4);
        byte[] nonce = new byte[12];
        Arrays.fill(nonce, (byte) 5);
        jdbcTemplate.update("""
                INSERT INTO iam_session.signing_keys (
                    id, algorithm, public_key_x509, encrypted_private_key,
                    encryption_nonce, protection_key_version, status,
                    created_at, activated_at, publish_until, retired_at, version
                ) VALUES (
                    'retired-cleanup-key', 'RS256', ?, ?, ?, 'test-v1', 'RETIRED',
                    ?, ?, ?, ?, 0
                )
                """,
                keyMaterial,
                keyMaterial,
                nonce,
                timestamp(NOW.minusSeconds(10800)),
                timestamp(NOW.minusSeconds(10800)),
                timestamp(NOW.minusSeconds(7300)),
                timestamp(NOW.minusSeconds(7200))
        );
        jdbcTemplate.update("""
                INSERT INTO iam_session.signing_key_events (
                    id, key_id, event_type, actor_id, operation_id, occurred_at
                ) VALUES (?, 'retired-cleanup-key', 'retired', 'test-operator', 'old-key-event', ?)
                """, UUID.randomUUID(), timestamp(old));
        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_client_events (
                    id, registered_client_id, event_type, actor_id, operation_id, occurred_at
                ) VALUES (?, 'bff:vk-platform-bff', 'registered', 'test-operator', 'old-client-event', ?)
                """, UUID.randomUUID(), timestamp(old));
    }

    private UUID insertSession(
            String status,
            Instant createdAt,
            Instant terminalAt,
            Instant revokedAt
    ) {
        UUID id = UUID.randomUUID();
        Instant expiresAt = status.equals("active") ? terminalAt : createdAt.plusSeconds(3600);
        jdbcTemplate.update("""
                INSERT INTO iam_session.sessions (
                    id, account_id, client_id, status, created_at, last_refreshed_at,
                    expires_at, revoked_at, revoke_reason, version
                ) VALUES (?, ?, 'vk-platform-bff', ?, ?, ?, ?, ?, ?, 0)
                """,
                id,
                ACCOUNT_ID,
                status,
                timestamp(createdAt),
                timestamp(createdAt),
                timestamp(expiresAt),
                revokedAt == null ? null : timestamp(revokedAt),
                revokedAt == null ? null : "user_request"
        );
        return id;
    }

    private void insertAuthorization(String id, UUID sessionId, Instant invalidatedAt) {
        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_authorizations (
                    id, registered_client_id, principal_name, session_id,
                    authorization_grant_type, authorized_scopes, attributes_json,
                    invalidated_at, invalidation_reason, version
                ) VALUES (
                    ?, 'bff:vk-platform-bff', ?, ?, 'authorization_code',
                    'api.access', '{}', ?, ?, 0
                )
                """,
                id,
                ACCOUNT_ID.toString(),
                sessionId,
                invalidatedAt == null ? null : timestamp(invalidatedAt),
                invalidatedAt == null ? null : "user_request"
        );
    }

    private void insertRefreshToken(String authorizationId, Instant issuedAt) {
        UUID id = UUID.randomUUID();
        byte[] digest = new byte[32];
        Arrays.fill(digest, (byte) 8);
        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_refresh_tokens (
                    id, authorization_id, family_id, token_digest, issued_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                authorizationId,
                id,
                digest,
                timestamp(issuedAt),
                timestamp(issuedAt.plusSeconds(600))
        );
    }

    private void insertAccount() {
        jdbcTemplate.update("""
                INSERT INTO iam_identity.accounts (
                    id, status, token_not_before, version, created_at, updated_at
                ) VALUES (?, 'active', ?, 0, ?, ?)
                """,
                ACCOUNT_ID,
                timestamp(NOW.minusSeconds(10800)),
                timestamp(NOW.minusSeconds(10800)),
                timestamp(NOW.minusSeconds(10800))
        );
    }

    private void insertBffClient() {
        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_clients (
                    id, client_id, client_id_issued_at, client_secret, client_name,
                    client_authentication_methods, authorization_grant_types,
                    redirect_uris, post_logout_redirect_uris, scopes,
                    client_settings, token_settings, version
                ) VALUES (
                    'bff:vk-platform-bff', 'vk-platform-bff', ?, '{noop}test-secret',
                    'Test BFF', 'client_secret_basic', 'authorization_code,refresh_token',
                    'https://web.example.test/callback', '', 'api.access', '{}', '{}', 0
                )
                """, timestamp(NOW.minusSeconds(10800)));
    }

    private static OperatorContext context(String operationId) {
        return new OperatorContext("retention-integration", operationId);
    }

    private static java.time.OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }

        @Bean
        @Primary
        PasswordlessIdentityOperations passwordlessIdentityOperations() {
            return mock(PasswordlessIdentityOperations.class);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            current.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
