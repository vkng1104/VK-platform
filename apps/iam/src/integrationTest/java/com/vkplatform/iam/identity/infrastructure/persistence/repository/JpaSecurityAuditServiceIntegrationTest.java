package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.api.SecurityAuditEventType;
import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.identity.api.command.RecordSecurityAuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaSecurityAuditServiceIntegrationTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("410644f0-31d0-488e-8272-5aaabc125323");
    private static final UUID SESSION_ID = UUID.fromString("85f80c5e-4af5-483d-947f-c1532a984936");
    private static final Instant NOW = Instant.parse("2026-10-30T04:00:00Z");

    @Autowired
    private SecurityAuditOperations audit;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetAudit() {
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("""
                INSERT INTO iam_identity.accounts (
                    id, status, token_not_before, version, created_at, updated_at
                ) VALUES (?, 'active', ?, 0, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                ACCOUNT_ID,
                timestamp(NOW.minusSeconds(1)),
                timestamp(NOW.minusSeconds(1)),
                timestamp(NOW.minusSeconds(1))
        );
    }

    @Test
    void recordsOnlyNarrowNonSecretCrossModuleContext() {
        audit.record(new RecordSecurityAuditEvent(
                SecurityAuditEventType.SESSION_CREATED,
                ACCOUNT_ID,
                SESSION_ID,
                "vk-platform-bff",
                "request-session-create",
                NOW
        ));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT event_type, account_id, target_id, actor_id, request_id, occurred_at
                FROM iam_identity.security_events
                WHERE account_id = ?
                """, ACCOUNT_ID))
                .containsEntry("event_type", "session_created")
                .containsEntry("account_id", ACCOUNT_ID)
                .containsEntry("target_id", SESSION_ID)
                .containsEntry("actor_id", "vk-platform-bff")
                .containsEntry("request_id", "request-session-create");
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'iam_identity' AND table_name = 'security_events'
                """, String.class)).doesNotContain(
                "token", "token_digest", "client_secret", "private_key", "exception"
        );
    }

    @Test
    void deletesOnlyTheBoundedStrictlyOlderAuditBatch() {
        record(SecurityAuditEventType.SESSION_REVOKED, NOW.minusSeconds(300), "old-1");
        record(SecurityAuditEventType.REFRESH_TOKEN_ROTATED, NOW.minusSeconds(200), "old-2");
        record(SecurityAuditEventType.REFRESH_TOKEN_REUSED, NOW.minusSeconds(100), "boundary");

        assertThat(audit.deleteRetainedBefore(NOW.minusSeconds(100), 1)).isOne();
        assertThat(audit.deleteRetainedBefore(NOW.minusSeconds(100), 1)).isOne();
        assertThat(audit.deleteRetainedBefore(NOW.minusSeconds(100), 1)).isZero();
        assertThat(jdbcTemplate.queryForList(
                "SELECT request_id FROM iam_identity.security_events WHERE account_id = ?",
                String.class,
                ACCOUNT_ID
        )).containsExactly("boundary");
    }

    private void record(SecurityAuditEventType type, Instant occurredAt, String requestId) {
        audit.record(new RecordSecurityAuditEvent(
                type,
                ACCOUNT_ID,
                SESSION_ID,
                ACCOUNT_ID.toString(),
                requestId,
                occurredAt
        ));
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
