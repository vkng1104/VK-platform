package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.domain.AuthenticationSession;
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
                "expires_at"
        );
    }

    @Test
    void createsAnActiveSessionWithOnlyTheExchangeState() {
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
    }

    @Test
    void databaseRejectsUnknownAccountsAndUnimplementedStatuses() {
        AuthenticationSession missingAccount = AuthenticationSession.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        );
        assertThatThrownBy(() -> repository.create(missingAccount))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO iam_session.sessions (
                    id, account_id, client_id, status, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                ACCOUNT_ID,
                "vk-platform-bff",
                "revoked",
                timestamp(NOW),
                timestamp(NOW.plusSeconds(3600))
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(SessionStatus.values()).containsExactly(SessionStatus.ACTIVE);
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
