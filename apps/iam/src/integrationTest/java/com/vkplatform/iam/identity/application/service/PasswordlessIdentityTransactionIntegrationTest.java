package com.vkplatform.iam.identity.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.command.AccountAdministrationCommand;
import com.vkplatform.iam.identity.api.command.VerifyPasswordlessSignIn;
import com.vkplatform.iam.identity.application.port.out.GrantTokenCodec;
import com.vkplatform.iam.identity.application.port.out.PasswordlessIdentityRepository;
import com.vkplatform.iam.identity.domain.IdentityPolicy;
import com.vkplatform.iam.verification.api.EmailVerificationOperations;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationRepository;
import com.vkplatform.iam.verification.application.port.out.VerificationCodeGenerator;
import com.vkplatform.iam.verification.application.port.out.VerificationCodeHasher;
import com.vkplatform.iam.verification.application.service.EmailVerificationService;
import com.vkplatform.iam.verification.domain.EmailAddress;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationCode;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "iam.email.cleanup-enabled=false")
@Import(PasswordlessIdentityTransactionIntegrationTest.TransactionTestConfiguration.class)
class PasswordlessIdentityTransactionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-11T05:00:00Z");
    private static final byte[] GRANT_DIGEST = bytes(21);

    @Autowired
    private PasswordlessIdentityOperations operations;

    @Autowired
    private PasswordlessIdentityRepository identities;

    @Autowired
    private EmailVerificationRepository verifications;

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
        jdbcTemplate.update("DELETE FROM iam_identity.email_verification_challenges");
    }

    @Test
    void failedGrantPersistenceRollsBackSuccessfulOtpConsumption() {
        identities.issueSignInGrant(
                bytes(22),
                GRANT_DIGEST,
                "vk-platform-web",
                NOW.plusSeconds(300),
                NOW,
                "seed-request"
        );
        UUID challengeId = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        VerificationChallenge challenge = new VerificationChallenge(
                challengeId,
                VerificationPurpose.SIGN_IN,
                bytes(23),
                bytes(24),
                bytes(25),
                5,
                NOW.plusSeconds(300),
                NOW.plusSeconds(60),
                NOW
        );
        verifications.createPending(challenge, VerificationPolicy.defaults());
        verifications.markSent(challengeId, NOW);

        assertThatThrownBy(() -> operations.verifySignIn(new VerifyPasswordlessSignIn(
                challengeId.toString(),
                "123456",
                "transaction-request"
        ))).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT verified_at FROM iam_identity.email_verification_challenges WHERE id = ?",
                Instant.class,
                challengeId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.verification_grants",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void accountSuspensionRevokesSessionsAndInvalidatesAuthorizationsBeforeCommit() {
        UUID accountId = identities.issueSignInGrant(
                bytes(31),
                bytes(32),
                "vk-platform-web",
                NOW.plusSeconds(300),
                NOW,
                "account-state-seed"
        ).accountId();
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO iam_session.sessions (
                    id, account_id, client_id, status, created_at, last_refreshed_at, expires_at
                ) VALUES (?, ?, 'vk-platform-bff', 'active', ?, ?, ?)
                """,
                sessionId,
                accountId,
                NOW.atOffset(ZoneOffset.UTC),
                NOW.atOffset(ZoneOffset.UTC),
                NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC)
        );
        jdbcTemplate.update("""
                INSERT INTO iam_session.oauth_authorizations (
                    id, registered_client_id, principal_name, session_id,
                    authorization_grant_type, authorized_scopes, attributes_json
                ) VALUES (?, 'bff:vk-platform-bff', ?, ?, 'authorization_code', 'api.access', '{}')
                """,
                "authorization-account-state",
                accountId.toString(),
                sessionId
        );

        operations.suspendAccount(new AccountAdministrationCommand(
                accountId,
                "admin-service",
                "account-state-request"
        ));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                sessionId
        )).isEqualTo("revoked");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoke_reason FROM iam_session.sessions WHERE id = ?",
                String.class,
                sessionId
        )).isEqualTo("account_state_changed");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT invalidation_reason FROM iam_session.oauth_authorizations WHERE id = ?",
                String.class,
                "authorization-account-state"
        )).isEqualTo("account_state_changed");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TransactionTestConfiguration {
        @Bean
        @Primary
        Clock transactionTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        EmailVerificationOperations transactionTestVerificationOperations(
                EmailVerificationRepository repository,
                Clock clock
        ) {
            VerificationCodeGenerator generator = () -> new VerificationCode("123456");
            VerificationCodeHasher hasher = new VerificationCodeHasher() {
                @Override
                public byte[] emailFingerprint(EmailAddress email) {
                    return bytes(23);
                }

                @Override
                public byte[] requesterFingerprint(String requesterAddress) {
                    return bytes(24);
                }

                @Override
                public byte[] codeDigest(UUID id, VerificationPurpose purpose, VerificationCode code) {
                    return bytes(25);
                }
            };
            return new EmailVerificationService(
                    repository,
                    (recipient, code, expiresAt) -> {
                    },
                    clock,
                    generator,
                    hasher,
                    VerificationPolicy.defaults()
            );
        }

        @Bean
        PasswordlessIdentityOperations transactionTestPasswordlessIdentityOperations(
                EmailVerificationOperations verification,
                PasswordlessIdentityRepository repository,
                Clock clock,
                IdentityPolicy policy,
                ApplicationEventPublisher events
        ) {
            GrantTokenCodec grants = new GrantTokenCodec() {
                @Override
                public GeneratedGrant generate() {
                    return new GeneratedGrant("A".repeat(43), GRANT_DIGEST);
                }

                @Override
                public byte[] digest(String grant) {
                    return GRANT_DIGEST.clone();
                }
            };
            return new PasswordlessIdentityService(
                    verification,
                    repository,
                    grants,
                    clock,
                    policy,
                    events
            );
        }
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
