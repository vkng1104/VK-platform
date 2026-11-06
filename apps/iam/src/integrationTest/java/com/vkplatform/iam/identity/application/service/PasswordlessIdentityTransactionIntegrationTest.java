package com.vkplatform.iam.identity.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.command.AccountAdministrationCommand;
import com.vkplatform.iam.identity.api.command.VerifyPasswordlessSignIn;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.identity.application.port.out.GrantTokenCodec;
import com.vkplatform.iam.identity.application.port.out.AccountRepository;
import com.vkplatform.iam.identity.application.port.out.IdentityOperationGuardRepository;
import com.vkplatform.iam.identity.application.port.out.LoginIdentityRepository;
import com.vkplatform.iam.identity.application.port.out.SecurityEventRepository;
import com.vkplatform.iam.identity.application.port.out.VerificationGrantRepository;
import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.LoginIdentity;
import com.vkplatform.iam.identity.domain.VerificationGrant;
import com.vkplatform.iam.identity.domain.IdentityPolicy;
import com.vkplatform.iam.verification.api.EmailVerificationOperations;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationChallengeRepository;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationOperationGuardRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
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
    private AccountRepository accounts;

    @Autowired
    private LoginIdentityRepository loginIdentities;

    @Autowired
    private VerificationGrantRepository verificationGrants;

    @Autowired
    private EmailVerificationChallengeRepository verifications;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
        seedAccountWithGrant(bytes(22), GRANT_DIGEST, NOW.plusSeconds(300), NOW);
        UUID challengeId = sentSignInChallenge(5);

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
    void wrongSignInCodeIncrementsFailedAttemptCountAndCommitsDespiteVerificationFailure() {
        UUID challengeId = sentSignInChallenge(5);

        assertThatThrownBy(() -> operations.verifySignIn(new VerifyPasswordlessSignIn(
                challengeId.toString(),
                "654321",
                "transaction-request"
        ))).isInstanceOf(VerificationFailure.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM iam_identity.email_verification_challenges WHERE id = ?",
                Integer.class,
                challengeId
        )).isEqualTo(1);
    }

    @Test
    void maxFailedAttemptsLocksOutSignInEvenWhenSubsequentCodeIsCorrect() {
        int maxAttempts = 3;
        UUID challengeId = sentSignInChallenge(maxAttempts);

        for (int i = 1; i <= maxAttempts; i++) {
            final int attempt = i;
            assertThatThrownBy(() -> operations.verifySignIn(new VerifyPasswordlessSignIn(
                    challengeId.toString(),
                    "654321",
                    "request-wrong-" + attempt
            ))).isInstanceOf(VerificationFailure.class);

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM iam_identity.email_verification_challenges WHERE id = ?",
                    Integer.class,
                    challengeId
            )).isEqualTo(attempt);
        }

        // Submitting the correct code ("123456") after lockout is rejected without issuing a grant
        assertThatThrownBy(() -> operations.verifySignIn(new VerifyPasswordlessSignIn(
                challengeId.toString(),
                "123456",
                "request-correct-after-lockout"
        ))).isInstanceOf(VerificationFailure.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM iam_identity.email_verification_challenges WHERE id = ?",
                Integer.class,
                challengeId
        )).isEqualTo(maxAttempts);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT verified_at FROM iam_identity.email_verification_challenges WHERE id = ?",
                Instant.class,
                challengeId
        )).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_identity.verification_grants",
                Integer.class
        )).isZero();
    }

    @Test
    void inactiveAccountLookupLeavesTheCallersTransactionCommittable() {
        UUID accountId = seedAccountWithGrant(bytes(41), bytes(42), NOW.plusSeconds(300), NOW);
        jdbcTemplate.update("UPDATE iam_identity.accounts SET status = 'suspended' WHERE id = ?", accountId);

        Optional<AuthenticatedAccount> found = new TransactionTemplate(transactionManager).execute(status -> {
            Optional<AuthenticatedAccount> lookup = operations.findActiveAccount(accountId);
            jdbcTemplate.update(
                    "UPDATE iam_identity.accounts SET updated_at = ? WHERE id = ?",
                    NOW.plusSeconds(1).atOffset(ZoneOffset.UTC),
                    accountId
            );
            return lookup;
        });

        assertThat(found).isEmpty();
        assertThat(timestamp("SELECT updated_at FROM iam_identity.accounts WHERE id = ?", accountId))
                .isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void accountSuspensionRevokesSessionsAndInvalidatesAuthorizationsBeforeCommit() {
        UUID accountId = seedAccountWithGrant(bytes(31), bytes(32), NOW.plusSeconds(240), NOW.minusSeconds(60));
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO iam_session.sessions (
                    id, account_id, client_id, status, created_at, last_refreshed_at, expires_at
                ) VALUES (?, ?, 'vk-platform-bff', 'active', ?, ?, ?)
                """,
                sessionId,
                accountId,
                NOW.minusSeconds(60).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(30).atOffset(ZoneOffset.UTC),
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
        assertThat(List.of(
                timestamp("SELECT updated_at FROM iam_identity.accounts WHERE id = ?", accountId),
                timestamp("SELECT token_not_before FROM iam_identity.accounts WHERE id = ?", accountId),
                timestamp("""
                        SELECT occurred_at
                        FROM iam_identity.security_events
                        WHERE account_id = ? AND event_type = 'account_suspended'
                        """, accountId),
                timestamp("SELECT revoked_at FROM iam_session.sessions WHERE id = ?", sessionId),
                timestamp(
                        "SELECT invalidated_at FROM iam_session.oauth_authorizations WHERE id = ?",
                        "authorization-account-state"
                )
        )).containsOnly(NOW);
    }

    private Instant timestamp(String sql, Object parameter) {
        return jdbcTemplate.queryForObject(sql, OffsetDateTime.class, parameter).toInstant();
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
                EmailVerificationChallengeRepository challenges,
                EmailVerificationOperationGuardRepository operationGuards,
                PlatformTransactionManager transactionManager,
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
                    return "123456".equals(code.value()) ? bytes(25) : bytes(26);
                }
            };
            return new EmailVerificationService(
                    challenges,
                    operationGuards,
                    new TransactionTemplate(transactionManager),
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
                AccountRepository accounts,
                LoginIdentityRepository loginIdentities,
                VerificationGrantRepository verificationGrants,
                SecurityEventRepository securityEvents,
                IdentityOperationGuardRepository operationGuards,
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
                    accounts,
                    loginIdentities,
                    verificationGrants,
                    securityEvents,
                    operationGuards,
                    grants,
                    clock,
                    policy,
                    events
            );
        }
    }

    /** Seeds what an earlier sign-in leaves behind: an account, its email identity, and a grant. */
    private UUID seedAccountWithGrant(byte[] fingerprint, byte[] grantDigest, Instant expiresAt, Instant createdAt) {
        Account account = Account.create(UUID.randomUUID(), createdAt);
        accounts.create(account);
        loginIdentities.create(LoginIdentity.linkVerifiedEmail(UUID.randomUUID(), account.id(), createdAt), fingerprint);
        verificationGrants.create(
                VerificationGrant.issueSignIn(UUID.randomUUID(), account.id(), "vk-platform-web", expiresAt, createdAt),
                fingerprint,
                grantDigest
        );
        return account.id();
    }

    private UUID sentSignInChallenge(int maxAttempts) {
        UUID challengeId = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        VerificationChallenge challenge = VerificationChallenge.pending(
                challengeId,
                VerificationPurpose.SIGN_IN,
                bytes(23),
                bytes(24),
                bytes(25),
                maxAttempts,
                NOW.plusSeconds(300),
                NOW.plusSeconds(60),
                NOW
        );
        verifications.create(challenge);
        verifications.markSent(challengeId, NOW);
        return challengeId;
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
