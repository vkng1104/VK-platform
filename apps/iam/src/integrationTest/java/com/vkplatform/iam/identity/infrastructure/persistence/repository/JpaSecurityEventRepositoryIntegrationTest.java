package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.SecurityEvent;
import com.vkplatform.iam.identity.domain.SecurityEventType;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.SecurityEventEntity_;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
class JpaSecurityEventRepositoryIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-06T04:00:00Z");

    @Autowired
    private JpaSecurityEventRepository repository;

    @Autowired
    private JpaAccountRepository accounts;

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
    }

    @Test
    void appendsAnEventWithAndWithoutAnActor() {
        assertThat(SecurityEventEntity_.eventType).isNotNull();
        Account account = Account.create(UUID.randomUUID(), NOW);
        accounts.create(account);
        SecurityEvent byUser = new SecurityEvent(
                UUID.randomUUID(), SecurityEventType.ACCOUNT_CREATED, account.id(), account.id(), null, "request-1", NOW
        );
        SecurityEvent byAdmin = new SecurityEvent(
                UUID.randomUUID(), SecurityEventType.ACCOUNT_SUSPENDED, account.id(), account.id(),
                "admin-service", "request-2", NOW.plusSeconds(5)
        );

        repository.append(byUser);
        repository.append(byAdmin);

        Map<String, Object> admin = jdbcTemplate.queryForMap(
                "SELECT event_type, actor_id, request_id FROM iam_identity.security_events WHERE id = ?",
                byAdmin.id()
        );
        assertThat(admin)
                .containsEntry("event_type", "account_suspended")
                .containsEntry("actor_id", "admin-service")
                .containsEntry("request_id", "request-2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT occurred_at FROM iam_identity.security_events WHERE id = ?",
                OffsetDateTime.class,
                byAdmin.id()
        ).toInstant()).isEqualTo(NOW.plusSeconds(5));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT actor_id FROM iam_identity.security_events WHERE id = ?",
                String.class,
                byUser.id()
        )).isNull();
    }
}
