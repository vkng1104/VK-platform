package com.vkplatform.iam.platform.persistence;

import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import com.vkplatform.iam.session.infrastructure.persistence.repository.JpaAuthenticationSessionRepository;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves, with real entities and Spring Data's own {@code save()}, that assigned-ID entities insert
 * without a select first and stay non-new once loaded.
 */
@SpringBootTest(properties = {
        "iam.email.cleanup-enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
class AssignedIdEntityPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-06T02:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JpaAuthenticationSessionRepository sessionRepository;

    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
        jdbcTemplate.update("DELETE FROM iam_identity.email_verification_challenges");
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
    }

    @Test
    void savesANewUuidEntityWithOneInsertAndNoSelect() {
        SessionEntity session = new SessionEntity(newSession(UUID.randomUUID()));
        assertThat(session.isNew()).isTrue();

        statistics().clear();
        SessionEntity saved = inTransaction(() -> repository(SessionEntity.class).saveAndFlush(session));

        assertThat(saved).isSameAs(session);
        assertThat(session.isNew()).isFalse();
        assertThat(statistics().getEntityInsertCount()).isEqualTo(1);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void savesANewStringIdEntityWithOneInsertAndNoSelect() {
        UUID sessionId = UUID.randomUUID();
        inTransaction(() -> repository(SessionEntity.class).saveAndFlush(new SessionEntity(newSession(sessionId))));
        OAuthAuthorizationEntity authorization = new OAuthAuthorizationEntity();
        authorization.id("authorization-assigned-id");
        authorization.registeredClientId("bff:vk-platform-bff");
        authorization.principalName(ACCOUNT_ID.toString());
        authorization.sessionId(sessionId);
        authorization.authorizationGrantType("authorization_code");
        authorization.authorizedScopes("api.access");
        authorization.attributesJson("{}");
        assertThat(authorization.isNew()).isTrue();

        statistics().clear();
        OAuthAuthorizationEntity saved = inTransaction(
                () -> repository(OAuthAuthorizationEntity.class).saveAndFlush(authorization)
        );

        assertThat(saved).isSameAs(authorization);
        assertThat(authorization.isNew()).isFalse();
        assertThat(statistics().getEntityInsertCount()).isEqualTo(1);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void savesANewChallengeWithOneInsertAndNoSelect() {
        EmailVerificationChallengeEntity challenge = new EmailVerificationChallengeEntity(
                UUID.randomUUID(),
                VerificationPurpose.RESTRICTED_RESOURCE_ACCESS,
                new byte[32],
                null,
                new byte[32],
                (short) 5,
                NOW.plusSeconds(600),
                NOW.plusSeconds(60),
                NOW
        );

        statistics().clear();
        EmailVerificationChallengeEntity saved = inTransaction(
                () -> repository(EmailVerificationChallengeEntity.class).saveAndFlush(challenge)
        );

        assertThat(saved).isSameAs(challenge);
        assertThat(challenge.isNew()).isFalse();
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void aReloadedEntityIsNotNewAndUpdatesInPlace() {
        UUID sessionId = UUID.randomUUID();
        inTransaction(() -> repository(SessionEntity.class).saveAndFlush(new SessionEntity(newSession(sessionId))));

        statistics().clear();
        inTransaction(() -> {
            entityManager.clear();
            SessionEntity loaded = entityManager.find(SessionEntity.class, sessionId);
            assertThat(loaded.isNew()).isFalse();
            loaded.apply(loaded.toDomain().revoke(SessionRevokeReason.LOGOUT, NOW.plusSeconds(30)));
            SessionEntity saved = repository(SessionEntity.class).saveAndFlush(loaded);
            assertThat(saved).isSameAs(loaded);
            return saved;
        });

        assertThat(statistics().getEntityInsertCount()).isZero();
        assertThat(statistics().getEntityUpdateCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                sessionId
        )).isEqualTo("revoked");
    }

    @Test
    void theSessionRepositoryCreatesASessionWithOneInsert() {
        statistics().clear();

        sessionRepository.create(newSession(UUID.randomUUID()));

        assertThat(statistics().getEntityInsertCount()).isEqualTo(1);
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
    }

    private <T> SimpleJpaRepository<T, Object> repository(Class<T> type) {
        return new SimpleJpaRepository<>(type, entityManager);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private static AuthenticationSession newSession(UUID sessionId) {
        return AuthenticationSession.create(sessionId, ACCOUNT_ID, "vk-platform-bff", NOW, NOW.plusSeconds(3600));
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
