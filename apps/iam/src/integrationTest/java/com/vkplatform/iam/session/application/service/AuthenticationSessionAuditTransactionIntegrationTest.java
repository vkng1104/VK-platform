package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.identity.api.command.RecordSecurityAuditEvent;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.api.AuthenticationSessionOperations;
import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import com.vkplatform.iam.session.domain.SessionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
@Import(AuthenticationSessionAuditTransactionIntegrationTest.TestBeans.class)
class AuthenticationSessionAuditTransactionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-30T05:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("64066873-433e-4f7a-a794-ae18fbd229be");

    @Autowired
    private AuthenticationSessionOperations operations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC)
        );
    }

    @Test
    void requiredAuditFailureRollsBackTheSessionMutation() {
        assertThatThrownBy(() -> operations.exchange(new ExchangeAuthenticationGrant(
                "A".repeat(43),
                "vk-platform-web",
                "audit-failure-request"
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated audit failure");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.sessions",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.security_events",
                Integer.class
        )).isZero();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        PasswordlessIdentityOperations identities() {
            PasswordlessIdentityOperations identities = mock(PasswordlessIdentityOperations.class);
            when(identities.consumeSignInGrant(any())).thenReturn(new AuthenticatedAccount(
                    ACCOUNT_ID,
                    NOW
            ));
            return identities;
        }

        @Bean
        @Primary
        SecurityAuditOperations failingAudit() {
            return new SecurityAuditOperations() {
                @Override
                public void record(RecordSecurityAuditEvent event) {
                    throw new IllegalStateException("simulated audit failure");
                }

                @Override
                public int deleteRetainedBefore(Instant cutoff, int batchSize) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Bean
        @Primary
        IamSecurityTelemetry telemetry() {
            return mock(IamSecurityTelemetry.class);
        }

        @Bean
        AuthenticationSessionOperations operations(
                PasswordlessIdentityOperations identities,
                AuthenticationSessionRepository sessions,
                SecurityAuditOperations audit,
                IamSecurityTelemetry telemetry,
                Clock clock
        ) {
            return new AuthenticationSessionService(
                    identities,
                    sessions,
                    audit,
                    telemetry,
                    new SessionPolicy("vk-platform-bff", Duration.ofDays(30)),
                    clock
            );
        }
    }
}
