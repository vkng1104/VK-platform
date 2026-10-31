package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.application.port.out.GrantTokenCodec;
import com.vkplatform.iam.identity.application.port.out.PasswordlessIdentityRepository;
import com.vkplatform.iam.identity.application.service.PasswordlessIdentityService;
import com.vkplatform.iam.identity.domain.IdentityPolicy;
import com.vkplatform.iam.session.api.AuthenticationSessionOperations;
import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionPolicy;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.verification.api.EmailVerificationOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
@Import(AuthenticationSessionTransactionIntegrationTest.TransactionTestConfiguration.class)
class AuthenticationSessionTransactionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-24T05:00:00Z");
    private static final byte[] GRANT_DIGEST = bytes(31);
    private static final String GRANT = "A".repeat(43);

    @Autowired
    private AuthenticationSessionOperations operations;

    @Autowired
    private PasswordlessIdentityRepository identities;

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
    void failedSessionCreationRollsBackGrantConsumptionAndItsAuditEvent() {
        identities.issueSignInGrant(
                bytes(32),
                GRANT_DIGEST,
                "vk-platform-web",
                NOW.plusSeconds(300),
                NOW,
                "seed-request"
        );

        assertThatThrownBy(() -> operations.exchange(new ExchangeAuthenticationGrant(
                GRANT,
                "vk-platform-web",
                "exchange-request"
        ))).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT consumed_at FROM iam_identity.verification_grants WHERE grant_digest = ?",
                Instant.class,
                GRANT_DIGEST
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.security_events WHERE event_type = 'sign_in_grant_consumed'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.sessions",
                Integer.class
        )).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionTestConfiguration {
        @Bean
        @Primary
        Clock transactionTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        AuthenticationSessionRepository failingAuthenticationSessionRepository() {
            return new AuthenticationSessionRepository() {
                @Override
                public AuthenticationSession create(AuthenticationSession session) {
                    throw new DataIntegrityViolationException("simulated session insert failure");
                }

                @Override
                public Optional<AuthenticationSession> findById(UUID id) {
                    return Optional.empty();
                }

                @Override
                public Optional<AuthenticationSession> findOwned(UUID accountId, UUID sessionId) {
                    return Optional.empty();
                }

                @Override
                public SessionPage findActiveByAccount(
                        UUID accountId,
                        Instant now,
                        int page,
                        int size
                ) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean revokeByIdForAccount(
                        UUID accountId,
                        UUID sessionId,
                        SessionRevokeReason reason,
                        Instant revokedAt
                ) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int revokeAll(
                        UUID accountId,
                        SessionRevokeReason reason,
                        Instant revokedAt
                ) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public boolean revokeOwnedAndInvalidate(
                        UUID accountId,
                        UUID sessionId,
                        SessionRevokeReason reason,
                        Instant revokedAt
                ) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public RevokeResult revokeOwnedFromCurrent(
                        UUID accountId,
                        UUID currentSessionId,
                        UUID targetSessionId,
                        String expectedClientId,
                        SessionRevokeReason reason,
                        Instant revokedAt
                ) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public RevokeAllResult revokeAllFromCurrent(
                        UUID accountId,
                        UUID currentSessionId,
                        String expectedClientId,
                        SessionRevokeReason reason,
                        Instant revokedAt
                ) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Bean
        PasswordlessIdentityOperations transactionTestPasswordlessIdentityOperations(
                PasswordlessIdentityRepository repository,
                Clock clock,
                IdentityPolicy policy
        ) {
            GrantTokenCodec grants = new GrantTokenCodec() {
                @Override
                public GeneratedGrant generate() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public byte[] digest(String grant) {
                    return GRANT_DIGEST.clone();
                }
            };
            return new PasswordlessIdentityService(
                    mock(EmailVerificationOperations.class),
                    repository,
                    grants,
                    clock,
                    policy
            );
        }

        @Bean
        AuthenticationSessionOperations transactionTestAuthenticationSessionOperations(
                PasswordlessIdentityOperations identities,
                AuthenticationSessionRepository sessions,
                Clock clock
        ) {
            return new AuthenticationSessionService(
                    identities,
                    sessions,
                    new SessionPolicy("vk-platform-bff", Duration.ofDays(30)),
                    clock
            );
        }
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
