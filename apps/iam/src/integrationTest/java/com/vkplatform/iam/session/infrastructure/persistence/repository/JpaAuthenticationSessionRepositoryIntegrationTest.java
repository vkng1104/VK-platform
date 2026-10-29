package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity_;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaAuthenticationSessionRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T04:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("2e2bd8da-d7df-480f-b8b8-2cd1073954c9");

    @Autowired
    private JpaAuthenticationSessionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
        insertAccount(ACCOUNT_ID);
    }

    @Test
    void flywaySchemaMatchesTheSingleGeneratedSessionEntity() {
        assertThat(SessionEntity_.id).isNotNull();
        assertThat(SessionEntity_.accountId).isNotNull();
        assertThat(SessionEntity_.status).isNotNull();
        assertThat(SessionEntity_.lastRefreshedAt).isNotNull();
        assertThat(SessionEntity_.revokedAt).isNotNull();
        assertThat(SessionEntity_.compromisedAt).isNotNull();
        assertThat(SessionEntity_.revokeReason).isNotNull();
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'iam_session'
                  AND table_name = 'sessions'
                """, String.class)).containsExactlyInAnyOrder(
                "id",
                "account_id",
                "client_id",
                "status",
                "created_at",
                "last_refreshed_at",
                "expires_at",
                "revoked_at",
                "compromised_at",
                "revoke_reason",
                "version"
        );
    }

    @Test
    void createsAnActiveSessionWithoutRevocationState() {
        UUID sessionId = UUID.fromString("f447ad02-3b85-4c4b-97bb-afbac7a93458");
        AuthenticationSession session = AuthenticationSession.create(
                sessionId,
                ACCOUNT_ID,
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        );

        AuthenticationSession created = repository.create(session);

        assertThat(created).isEqualTo(session);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                sessionId
        )).isEqualTo("active");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT expires_at FROM iam_session.sessions WHERE id = ?",
                Instant.class,
                sessionId
        )).isEqualTo(NOW.plusSeconds(3600));
        assertThat(jdbcTemplate.queryForMap(
                "SELECT revoked_at, revoke_reason FROM iam_session.sessions WHERE id = ?",
                sessionId
        )).containsEntry("revoked_at", null).containsEntry("revoke_reason", null);
    }

    @Test
    void databaseRejectsUnknownAccounts() {
        AuthenticationSession missingAccount = AuthenticationSession.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        );
        assertThatThrownBy(() -> repository.create(missingAccount))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revokesOnlyTheOwnedSessionAndPreservesTheFirstTransition() {
        UUID sessionId = UUID.randomUUID();
        AuthenticationSession active = createSession(sessionId, ACCOUNT_ID);
        repository.create(active);

        assertThat(repository.revokeByIdForAccount(
                UUID.randomUUID(),
                sessionId,
                SessionRevokeReason.USER_REQUEST,
                NOW.plusSeconds(10)
        )).isFalse();
        assertThat(repository.findById(sessionId)).contains(active);

        assertThat(repository.revokeByIdForAccount(
                ACCOUNT_ID,
                sessionId,
                SessionRevokeReason.USER_REQUEST,
                NOW.plusSeconds(20)
        )).isTrue();
        AuthenticationSession revoked = repository.findById(sessionId).orElseThrow();
        assertThat(revoked.status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(revoked.revokeReason()).isEqualTo(SessionRevokeReason.USER_REQUEST);

        assertThat(repository.revokeByIdForAccount(
                ACCOUNT_ID,
                sessionId,
                SessionRevokeReason.LOGOUT,
                NOW.plusSeconds(30)
        )).isTrue();
        assertThat(repository.findById(sessionId)).contains(revoked);
    }

    @Test
    void revokesEveryActiveSessionForOneAccount() {
        UUID otherAccountId = UUID.randomUUID();
        insertAccount(otherAccountId);
        AuthenticationSession first = repository.create(createSession(UUID.randomUUID(), ACCOUNT_ID));
        AuthenticationSession second = repository.create(createSession(UUID.randomUUID(), ACCOUNT_ID));
        AuthenticationSession other = repository.create(createSession(UUID.randomUUID(), otherAccountId));

        assertThat(repository.revokeAll(
                ACCOUNT_ID,
                SessionRevokeReason.ALL_SESSIONS_REVOKED,
                NOW.plusSeconds(40)
        )).isEqualTo(2);
        assertThat(repository.revokeAll(
                ACCOUNT_ID,
                SessionRevokeReason.ALL_SESSIONS_REVOKED,
                NOW.plusSeconds(50)
        )).isZero();
        assertThat(repository.findById(first.id()).orElseThrow().status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(repository.findById(second.id()).orElseThrow().status()).isEqualTo(SessionStatus.REVOKED);
        assertThat(repository.findById(other.id())).contains(other);
    }

    @Test
    void databaseRejectsInconsistentRevocationState() {
        assertThatThrownBy(() -> insertRawSession(
                SessionStatus.REVOKED.wireValue(),
                null,
                null,
                NOW
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRawSession(
                SessionStatus.ACTIVE.wireValue(),
                NOW.plusSeconds(1),
                SessionRevokeReason.USER_REQUEST.wireValue(),
                NOW
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRawSession(
                SessionStatus.REVOKED.wireValue(),
                NOW.plusSeconds(1),
                "refresh_token_reuse",
                NOW
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRawSession(
                SessionStatus.REVOKED.wireValue(),
                NOW.minusSeconds(1),
                SessionRevokeReason.USER_REQUEST.wireValue(),
                NOW
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRawSession(
            String status,
            Instant revokedAt,
            String revokeReason,
            Instant createdAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO iam_session.sessions (
                    id, account_id, client_id, status, created_at, last_refreshed_at, expires_at,
                    revoked_at, revoke_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                ACCOUNT_ID,
                "vk-platform-bff",
                status,
                timestamp(createdAt),
                timestamp(createdAt),
                timestamp(createdAt.plusSeconds(3600)),
                revokedAt == null ? null : timestamp(revokedAt),
                revokeReason
        );
    }

    private AuthenticationSession createSession(UUID sessionId, UUID accountId) {
        return AuthenticationSession.create(
                sessionId,
                accountId,
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        );
    }

    private void insertAccount(UUID accountId) {
        jdbcTemplate.update("""
                INSERT INTO iam_identity.accounts (
                    id, status, token_not_before, version, created_at, updated_at
                ) VALUES (?, 'active', ?, 0, ?, ?)
                """,
                accountId,
                timestamp(NOW),
                timestamp(NOW),
                timestamp(NOW)
        );
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
