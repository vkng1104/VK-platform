package com.vkplatform.iam.identity.application.service;

import com.vkplatform.iam.identity.api.command.AccountAdministrationCommand;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.command.StartPasswordlessSignIn;
import com.vkplatform.iam.identity.api.command.VerifyPasswordlessSignIn;
import com.vkplatform.iam.identity.api.AccountAuthenticationInvalidated;
import com.vkplatform.iam.identity.api.result.AccountSummary;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.identity.api.result.AuthenticationChallengeStarted;
import com.vkplatform.iam.identity.api.result.AuthenticationGrantIssued;
import com.vkplatform.iam.identity.application.port.out.GrantTokenCodec;
import com.vkplatform.iam.identity.application.port.out.PasswordlessIdentityRepository;
import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.AccountStatus;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.identity.domain.IdentityPolicy;
import com.vkplatform.iam.verification.api.EmailVerificationOperations;
import com.vkplatform.iam.verification.api.command.StartEmailVerification;
import com.vkplatform.iam.verification.api.command.StartSignInEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordlessIdentityServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T02:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("3d64af52-d808-4cf0-a864-c7ed43f1f802");
    private static final String GRANT = "A".repeat(43);

    private RecordingVerification verification;
    private RecordingRepository repository;
    private SteppingClock clock;
    private List<Object> events;
    private PasswordlessIdentityService service;

    @BeforeEach
    void setUp() {
        verification = new RecordingVerification();
        repository = new RecordingRepository();
        clock = new SteppingClock(NOW);
        events = new ArrayList<>();
        GrantTokenCodec grants = new GrantTokenCodec() {
            @Override
            public GeneratedGrant generate() {
                return new GeneratedGrant(GRANT, bytes(9));
            }

            @Override
            public byte[] digest(String grant) {
                if (!GRANT.equals(grant)) {
                    throw IdentityFailure.invalidGrant();
                }
                return bytes(9);
            }
        };
        service = new PasswordlessIdentityService(
                verification,
                repository,
                grants,
                clock,
                new IdentityPolicy("vk-platform-web", Duration.ofMinutes(5)),
                events::add
        );
    }

    @Test
    void startsOnlyTheExplicitSignInVerificationFlow() {
        AuthenticationChallengeStarted started = service.startSignIn(
                new StartPasswordlessSignIn("reader@example.com", "192.0.2.20")
        );

        assertThat(verification.signInStart.email()).isEqualTo("reader@example.com");
        assertThat(verification.signInStart.requesterAddress()).isEqualTo("192.0.2.20");
        assertThat(verification.compatibilityStartCalled).isFalse();
        assertThat(started.id()).isEqualTo(RecordingVerification.CHALLENGE_ID);
    }

    @Test
    void verifiesIdentityAndIssuesAnAudienceBoundOneTimeGrant() {
        AuthenticationGrantIssued issued = service.verifySignIn(new VerifyPasswordlessSignIn(
                RecordingVerification.CHALLENGE_ID.toString(),
                "123456",
                "request-1"
        ));

        assertThat(verification.signInVerify.code()).isEqualTo("123456");
        assertThat(repository.issuedFingerprint).containsOnly((byte) 7);
        assertThat(repository.issuedDigest).containsOnly((byte) 9);
        assertThat(repository.issuedAudience).isEqualTo("vk-platform-web");
        assertThat(repository.issuedAt).isEqualTo(NOW);
        assertThat(repository.issuedExpiresAt).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(repository.requestId).isEqualTo("request-1");
        assertThat(issued.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(issued.grant()).isEqualTo(GRANT);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void consumesOnlyTheConfiguredAudience() {
        AuthenticatedAccount authenticated = service.consumeSignInGrant(new ConsumeSignInGrant(
                GRANT,
                "vk-platform-web",
                "request-2"
        ));

        assertThat(repository.consumedDigest).containsOnly((byte) 9);
        assertThat(authenticated.accountId()).isEqualTo(ACCOUNT_ID);

        assertThatThrownBy(() -> service.consumeSignInGrant(new ConsumeSignInGrant(
                GRANT,
                "another-audience",
                "request-3"
        ))).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_AUDIENCE));
        assertThat(repository.consumeCalls).isEqualTo(1);
    }

    @Test
    void delegatesAuditedAdministrativeTransitions() {
        var command = new AccountAdministrationCommand(ACCOUNT_ID, "admin-service", "request-4");

        assertThat(service.suspendAccount(command).status()).isEqualTo("suspended");
        assertThat(repository.recordedAccount.status()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(service.reactivateAccount(command).status()).isEqualTo("active");
        assertThat(repository.recordedAccount.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(service.deleteAccount(command).status()).isEqualTo("deleted");
        assertThat(repository.recordedAccount.status()).isEqualTo(AccountStatus.DELETED);
        assertThat(repository.recordedActorId).isEqualTo("admin-service");
        assertThat(repository.recordedRequestId).isEqualTo("request-4");
        assertThat(events).containsExactly(
                new AccountAuthenticationInvalidated(ACCOUNT_ID, NOW),
                new AccountAuthenticationInvalidated(ACCOUNT_ID, NOW),
                new AccountAuthenticationInvalidated(ACCOUNT_ID, NOW)
        );
    }

    @Test
    void samplesTheTransitionTimeAfterLockingTheAccount() {
        Instant lockedAt = NOW.plusSeconds(5);
        repository.onStatusChangeLock = () -> clock.advanceTo(lockedAt);

        AccountSummary summary = service.suspendAccount(new AccountAdministrationCommand(
                ACCOUNT_ID,
                "admin-service",
                "request-locked"
        ));

        assertThat(repository.statusChangeLookup).isEqualTo(ACCOUNT_ID);
        assertThat(repository.recordedAt).isEqualTo(lockedAt);
        assertThat(repository.recordedAccount.updatedAt()).isEqualTo(lockedAt);
        assertThat(repository.recordedAccount.tokenNotBefore()).isEqualTo(lockedAt);
        assertThat(summary.updatedAt()).isEqualTo(lockedAt);
        assertThat(events).containsExactly(new AccountAuthenticationInvalidated(ACCOUNT_ID, lockedAt));
    }

    @Test
    void doesNotPublishInvalidationForAnIdempotentAccountTransition() {
        repository.statusChangeAccount = RecordingRepository.account(AccountStatus.SUSPENDED, null);

        service.suspendAccount(new AccountAdministrationCommand(
                ACCOUNT_ID,
                "admin-service",
                "request-idempotent"
        ));

        assertThat(repository.recordCalls).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void findsOnlyAnActiveAccountForTokenIssuanceWithoutThrowing() {
        assertThat(service.findActiveAccount(ACCOUNT_ID))
                .hasValue(new AuthenticatedAccount(ACCOUNT_ID, NOW));
        assertThat(repository.tokenIssuanceLookup).isEqualTo(ACCOUNT_ID);

        repository.tokenIssuanceAccount = Optional.of(RecordingRepository.account(AccountStatus.SUSPENDED, null));
        assertThat(service.findActiveAccount(ACCOUNT_ID)).isEmpty();

        repository.tokenIssuanceAccount = Optional.of(RecordingRepository.account(AccountStatus.DELETED, NOW));
        assertThat(service.findActiveAccount(ACCOUNT_ID)).isEmpty();

        repository.tokenIssuanceAccount = Optional.empty();
        assertThat(service.findActiveAccount(ACCOUNT_ID)).isEmpty();
    }

    @Test
    void rejectsMissingOrOversizedAuditContext() {
        assertThatThrownBy(() -> service.verifySignIn(new VerifyPasswordlessSignIn(
                RecordingVerification.CHALLENGE_ID.toString(),
                "123456",
                " "
        ))).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_ADMIN_CONTEXT));
        assertThatThrownBy(() -> service.suspendAccount(new AccountAdministrationCommand(
                ACCOUNT_ID,
                "x".repeat(129),
                "request"
        ))).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_ADMIN_CONTEXT));
        assertThat(repository.statusChangeLookup).isNull();
    }

    private static final class RecordingVerification implements EmailVerificationOperations {
        private static final UUID CHALLENGE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        private StartSignInEmailVerification signInStart;
        private VerifyEmailCode signInVerify;
        private boolean compatibilityStartCalled;

        @Override
        public VerificationChallengeStarted start(StartEmailVerification command) {
            compatibilityStartCalled = true;
            throw new UnsupportedOperationException();
        }

        @Override
        public VerificationChallengeStarted startSignIn(StartSignInEmailVerification command) {
            signInStart = command;
            return new VerificationChallengeStarted(
                    CHALLENGE_ID,
                    "r****r@example.com",
                    NOW.plusSeconds(300),
                    NOW.plusSeconds(60)
            );
        }

        @Override
        public EmailVerified verify(VerifyEmailCode command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmailVerified verifySignIn(VerifyEmailCode command) {
            signInVerify = command;
            return new EmailVerified(CHALLENGE_ID, "sign_in", NOW, bytes(7));
        }
    }

    private static final class RecordingRepository implements PasswordlessIdentityRepository {
        private byte[] issuedFingerprint;
        private byte[] issuedDigest;
        private String issuedAudience;
        private Instant issuedExpiresAt;
        private Instant issuedAt;
        private String requestId;
        private byte[] consumedDigest;
        private int consumeCalls;
        private UUID tokenIssuanceLookup;
        private Optional<Account> tokenIssuanceAccount = Optional.of(account(AccountStatus.ACTIVE, null));
        private Account statusChangeAccount = account(AccountStatus.ACTIVE, null);
        private Runnable onStatusChangeLock = () -> {
        };
        private UUID statusChangeLookup;
        private Account recordedAccount;
        private String recordedActorId;
        private String recordedRequestId;
        private Instant recordedAt;
        private int recordCalls;

        @Override
        public IssuedGrant issueSignInGrant(
                byte[] identityFingerprint,
                byte[] grantDigest,
                String audience,
                Instant expiresAt,
                Instant now,
                String requestId
        ) {
            issuedFingerprint = identityFingerprint.clone();
            issuedDigest = grantDigest.clone();
            issuedAudience = audience;
            issuedExpiresAt = expiresAt;
            issuedAt = now;
            this.requestId = requestId;
            return new IssuedGrant(ACCOUNT_ID, expiresAt);
        }

        @Override
        public Account consumeSignInGrant(
                byte[] grantDigest,
                String audience,
                Instant now,
                String requestId
        ) {
            consumeCalls++;
            consumedDigest = grantDigest.clone();
            return account(AccountStatus.ACTIVE, null);
        }

        @Override
        public Account findAccount(UUID accountId) {
            return account(AccountStatus.ACTIVE, null);
        }

        @Override
        public Optional<Account> findAccountForTokenIssuance(UUID accountId) {
            tokenIssuanceLookup = accountId;
            return tokenIssuanceAccount;
        }

        @Override
        public Account findAccountForStatusChange(UUID accountId) {
            statusChangeLookup = accountId;
            onStatusChangeLock.run();
            return statusChangeAccount;
        }

        @Override
        public void recordAccountStatusChange(
                Account changed,
                String actorId,
                String requestId,
                Instant transitionAt
        ) {
            recordCalls++;
            recordedAccount = changed;
            recordedActorId = actorId;
            recordedRequestId = requestId;
            recordedAt = transitionAt;
            statusChangeAccount = changed;
        }

        private static Account account(AccountStatus status, Instant deletedAt) {
            return new Account(ACCOUNT_ID, status, NOW, 0, NOW, NOW, deletedAt);
        }
    }

    private static final class SteppingClock extends Clock {
        private Instant now;

        private SteppingClock(Instant now) {
            this.now = now;
        }

        private void advanceTo(Instant next) {
            now = next;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }
}
