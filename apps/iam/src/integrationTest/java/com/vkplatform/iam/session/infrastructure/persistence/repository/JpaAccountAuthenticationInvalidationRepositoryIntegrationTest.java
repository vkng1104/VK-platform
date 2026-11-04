package com.vkplatform.iam.session.infrastructure.persistence.repository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vkplatform.iam.session.application.port.out.AccountAuthenticationInvalidationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaAccountAuthenticationInvalidationRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T06:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("5a3c1d2e-7b8f-4c6d-9e0a-1b2c3d4e5f60");
    private static final UUID SESSION_ID = UUID.fromString("6b4d2e3f-8c9a-4d7e-8f1b-2c3d4e5f6a71");
    private static final String AUTHORIZATION_ID = "authorization-account-invalidation";

    @Autowired
    private AccountAuthenticationInvalidationRepository invalidations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final Logger logger = (Logger) LoggerFactory.getLogger(
            JpaAccountAuthenticationInvalidationRepository.class
    );
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void cleanDatabaseAndCaptureLogs() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingLogs() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void usesTheEventTimeWhenItFollowsTheLastRefresh() {
        insertAccountSessionAndAuthorization(NOW.plusSeconds(10));

        invalidations.invalidateAccount(ACCOUNT_ID, NOW.plusSeconds(30));

        assertThat(revokedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(invalidatedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(appender.list).isEmpty();
    }

    @Test
    void clampsAnEventTimeEarlierThanTheLastRefreshAndLogsTheAnomaly() {
        Instant lastRefreshedAt = NOW.plusSeconds(30);
        insertAccountSessionAndAuthorization(lastRefreshedAt);

        invalidations.invalidateAccount(ACCOUNT_ID, NOW.plusSeconds(10));

        assertThat(revokedAt()).isEqualTo(lastRefreshedAt);
        assertThat(invalidatedAt()).isEqualTo(lastRefreshedAt);
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("Clamped account invalidation", SESSION_ID.toString());
        });
    }

    private void insertAccountSessionAndAuthorization(Instant lastRefreshedAt) {
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
                timestamp(lastRefreshedAt),
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

    private Instant revokedAt() {
        return jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM iam_session.sessions WHERE id = ?",
                OffsetDateTime.class,
                SESSION_ID
        ).toInstant();
    }

    private Instant invalidatedAt() {
        return jdbcTemplate.queryForObject(
                "SELECT invalidated_at FROM iam_session.oauth_authorizations WHERE id = ?",
                OffsetDateTime.class,
                AUTHORIZATION_ID
        ).toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
