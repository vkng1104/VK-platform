package com.vkplatform.iam.identity.application.service;

import com.vkplatform.iam.identity.api.AccountAuthenticationInvalidated;
import com.vkplatform.iam.identity.api.command.AccountAdministrationCommand;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.command.StartPasswordlessSignIn;
import com.vkplatform.iam.identity.api.command.VerifyPasswordlessSignIn;
import com.vkplatform.iam.identity.api.result.AccountSummary;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.identity.api.result.AuthenticationChallengeStarted;
import com.vkplatform.iam.identity.api.result.AuthenticationGrantIssued;
import com.vkplatform.iam.identity.application.port.out.AccountRepository;
import com.vkplatform.iam.identity.application.port.out.GrantTokenCodec;
import com.vkplatform.iam.identity.application.port.out.IdentityOperationGuardRepository;
import com.vkplatform.iam.identity.application.port.out.LoginIdentityRepository;
import com.vkplatform.iam.identity.application.port.out.SecurityEventRepository;
import com.vkplatform.iam.identity.application.port.out.VerificationGrantRepository;
import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.AccountStatus;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.identity.domain.IdentityKind;
import com.vkplatform.iam.identity.domain.IdentityPolicy;
import com.vkplatform.iam.identity.domain.LoginIdentity;
import com.vkplatform.iam.identity.domain.SecurityEvent;
import com.vkplatform.iam.identity.domain.SecurityEventType;
import com.vkplatform.iam.identity.domain.VerificationGrant;
import com.vkplatform.iam.verification.api.EmailVerificationOperations;
import com.vkplatform.iam.verification.api.command.StartEmailVerification;
import com.vkplatform.iam.verification.api.command.StartSignInEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PasswordlessIdentityServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T02:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("3d64af52-d808-4cf0-a864-c7ed43f1f802");
    private static final String GRANT = "A".repeat(43);
    private static final String AUDIENCE = "vk-platform-web";

    private RecordingVerification verification;
    private AccountRepository accounts;
    private LoginIdentityRepository loginIdentities;
    private VerificationGrantRepository verificationGrants;
    private SecurityEventRepository securityEvents;
    private IdentityOperationGuardRepository operationGuards;
    private SteppingClock clock;
    private List<Object> events;
    private PasswordlessIdentityService service;

    @BeforeEach
    void setUp() {
        verification = new RecordingVerification();
        accounts = mock(AccountRepository.class);
        loginIdentities = mock(LoginIdentityRepository.class);
        verificationGrants = mock(VerificationGrantRepository.class);
        securityEvents = mock(SecurityEventRepository.class);
        operationGuards = mock(IdentityOperationGuardRepository.class);
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
                accounts,
                loginIdentities,
                verificationGrants,
                securityEvents,
                operationGuards,
                grants,
                clock,
                new IdentityPolicy(AUDIENCE, Duration.ofMinutes(5)),
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
    void firstSignInCreatesTheAccountAndIdentityUnderTheGuardThenIssuesADigestOnlyGrant() {
        when(loginIdentities.findByKindAndFingerprint(eq(IdentityKind.EMAIL), any()))
                .thenReturn(Optional.empty());

        AuthenticationGrantIssued issued = service.verifySignIn(verifyCommand("request-1"));

        assertThat(verification.signInVerify.code()).isEqualTo("123456");
        ArgumentCaptor<Account> created = ArgumentCaptor.forClass(Account.class);
        ArgumentCaptor<LoginIdentity> linked = ArgumentCaptor.forClass(LoginIdentity.class);
        ArgumentCaptor<VerificationGrant> grant = ArgumentCaptor.forClass(VerificationGrant.class);
        ArgumentCaptor<byte[]> fingerprint = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> digest = ArgumentCaptor.forClass(byte[].class);
        InOrder order = inOrder(operationGuards, loginIdentities, accounts, verificationGrants);
        order.verify(operationGuards).lockForIdentifier(any());
        order.verify(loginIdentities).findByKindAndFingerprint(eq(IdentityKind.EMAIL), any());
        order.verify(accounts).create(created.capture());
        order.verify(loginIdentities).create(linked.capture(), any());
        order.verify(verificationGrants).create(grant.capture(), fingerprint.capture(), digest.capture());

        Account account = created.getValue();
        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.createdAt()).isEqualTo(NOW);
        assertThat(linked.getValue().accountId()).isEqualTo(account.id());
        assertThat(linked.getValue().kind()).isEqualTo(IdentityKind.EMAIL);
        assertThat(grant.getValue().accountId()).isEqualTo(account.id());
        assertThat(grant.getValue().audience()).isEqualTo(AUDIENCE);
        assertThat(grant.getValue().createdAt()).isEqualTo(NOW);
        assertThat(grant.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(fingerprint.getValue()).containsOnly((byte) 7);
        assertThat(digest.getValue()).containsOnly((byte) 9);
        assertThat(recordedEventTypes()).containsExactly(
                SecurityEventType.ACCOUNT_CREATED,
                SecurityEventType.LOGIN_IDENTITY_LINKED,
                SecurityEventType.SIGN_IN_GRANT_ISSUED
        );
        assertThat(recordedEvents()).allSatisfy(event -> {
            assertThat(event.requestId()).isEqualTo("request-1");
            assertThat(event.occurredAt()).isEqualTo(NOW);
            assertThat(event.actorId()).isNull();
        });
        assertThat(issued.accountId()).isEqualTo(account.id());
        assertThat(issued.grant()).isEqualTo(GRANT);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void laterSignInLocksTheExistingAccountAndCreatesNothingButTheGrant() {
        existingIdentity();
        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));

        AuthenticationGrantIssued issued = service.verifySignIn(verifyCommand("request-2"));

        assertThat(issued.accountId()).isEqualTo(ACCOUNT_ID);
        verify(accounts, never()).create(any());
        verify(loginIdentities, never()).create(any(), any());
        verify(verificationGrants).create(any(), any(), any());
        assertThat(recordedEventTypes()).containsExactly(SecurityEventType.SIGN_IN_GRANT_ISSUED);
    }

    @Test
    void signInIsRefusedForAnInactiveOrMissingAccountWithoutIssuingAGrant() {
        existingIdentity();
        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.of(account(AccountStatus.SUSPENDED)));
        assertThatThrownBy(() -> service.verifySignIn(verifyCommand("request-3")))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_UNAVAILABLE));

        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.verifySignIn(verifyCommand("request-4")))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_NOT_FOUND));

        verifyNoInteractions(verificationGrants, securityEvents);
    }

    @Test
    void propagatesVerificationFailureWithoutInteractingWithIdentityStorage() {
        verification.failSignInWith(VerificationFailure.invalidOrExpiredCode());

        assertThatThrownBy(() -> service.verifySignIn(verifyCommand("request-fail")))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_OR_EXPIRED_CODE));

        verifyNoInteractions(accounts, loginIdentities, verificationGrants, securityEvents, operationGuards);
    }

    @Test
    void consumesAnUnusedGrantOnceAndAuditsIt() {
        VerificationGrant grant = grant(NOW.plusSeconds(60));
        when(verificationGrants.findLockedByDigest(any())).thenReturn(Optional.of(grant));
        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));

        AuthenticatedAccount authenticated = service.consumeSignInGrant(consumeCommand(AUDIENCE));

        assertThat(authenticated).isEqualTo(new AuthenticatedAccount(ACCOUNT_ID, NOW));
        InOrder order = inOrder(verificationGrants, accounts, securityEvents);
        order.verify(verificationGrants).findLockedByDigest(any());
        order.verify(accounts).findLockedById(ACCOUNT_ID);
        order.verify(verificationGrants).recordConsumption(grant.consume(NOW));
        order.verify(securityEvents).append(any());
        assertThat(recordedEventTypes()).containsExactly(SecurityEventType.SIGN_IN_GRANT_CONSUMED);
        assertThat(recordedEvents().getFirst().targetId()).isEqualTo(grant.id());
    }

    @Test
    void rejectsAnotherAudienceBeforeLookingTheGrantUp() {
        assertThatThrownBy(() -> service.consumeSignInGrant(consumeCommand("another-audience")))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_AUDIENCE));
        verifyNoInteractions(verificationGrants, accounts, securityEvents);
    }

    @Test
    void rejectsUnknownUsedOrExpiredGrantsWithoutLockingTheAccount() {
        when(verificationGrants.findLockedByDigest(any())).thenReturn(Optional.empty());
        assertInvalidGrant();

        when(verificationGrants.findLockedByDigest(any()))
                .thenReturn(Optional.of(grant(NOW.plusSeconds(60)).consume(NOW.minusSeconds(1))));
        assertInvalidGrant();

        when(verificationGrants.findLockedByDigest(any())).thenReturn(Optional.of(grant(NOW)));
        assertInvalidGrant();

        verifyNoInteractions(accounts, securityEvents);
        verify(verificationGrants, never()).recordConsumption(any());
    }

    @Test
    void doesNotConsumeAGrantForAnInactiveAccount() {
        when(verificationGrants.findLockedByDigest(any())).thenReturn(Optional.of(grant(NOW.plusSeconds(60))));
        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.of(account(AccountStatus.DELETED)));

        assertThatThrownBy(() -> service.consumeSignInGrant(consumeCommand(AUDIENCE)))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_UNAVAILABLE));
        verify(verificationGrants, never()).recordConsumption(any());
        verifyNoInteractions(securityEvents);
    }

    @Test
    void auditsEachAdministrativeTransitionAndPublishesInvalidation() {
        when(accounts.findLockedById(ACCOUNT_ID))
                .thenReturn(Optional.of(account(AccountStatus.ACTIVE)))
                .thenReturn(Optional.of(account(AccountStatus.SUSPENDED)))
                .thenReturn(Optional.of(account(AccountStatus.ACTIVE)));
        var command = new AccountAdministrationCommand(ACCOUNT_ID, "admin-service", "request-5");

        assertThat(service.suspendAccount(command).status()).isEqualTo("suspended");
        assertThat(service.reactivateAccount(command).status()).isEqualTo("active");
        assertThat(service.deleteAccount(command).status()).isEqualTo("deleted");

        ArgumentCaptor<Account> recorded = ArgumentCaptor.forClass(Account.class);
        verify(accounts, times(3)).recordStatusChange(recorded.capture());
        assertThat(recorded.getAllValues()).extracting(Account::status).containsExactly(
                AccountStatus.SUSPENDED,
                AccountStatus.ACTIVE,
                AccountStatus.DELETED
        );
        assertThat(recordedEventTypes()).containsExactly(
                SecurityEventType.ACCOUNT_SUSPENDED,
                SecurityEventType.ACCOUNT_REACTIVATED,
                SecurityEventType.ACCOUNT_DELETED
        );
        assertThat(recordedEvents()).allSatisfy(event -> {
            assertThat(event.actorId()).isEqualTo("admin-service");
            assertThat(event.requestId()).isEqualTo("request-5");
            assertThat(event.targetId()).isEqualTo(ACCOUNT_ID);
        });
        assertThat(events).containsExactly(
                new AccountAuthenticationInvalidated(ACCOUNT_ID, NOW),
                new AccountAuthenticationInvalidated(ACCOUNT_ID, NOW),
                new AccountAuthenticationInvalidated(ACCOUNT_ID, NOW)
        );
    }

    @Test
    void samplesTheTransitionTimeAfterLockingTheAccount() {
        Instant lockedAt = NOW.plusSeconds(5);
        when(accounts.findLockedById(ACCOUNT_ID)).thenAnswer(invocation -> {
            clock.advanceTo(lockedAt);
            return Optional.of(account(AccountStatus.ACTIVE));
        });

        AccountSummary summary = service.suspendAccount(new AccountAdministrationCommand(
                ACCOUNT_ID,
                "admin-service",
                "request-locked"
        ));

        ArgumentCaptor<Account> recorded = ArgumentCaptor.forClass(Account.class);
        verify(accounts).recordStatusChange(recorded.capture());
        assertThat(recorded.getValue().updatedAt()).isEqualTo(lockedAt);
        assertThat(recorded.getValue().tokenNotBefore()).isEqualTo(lockedAt);
        assertThat(recordedEvents().getFirst().occurredAt()).isEqualTo(lockedAt);
        assertThat(summary.updatedAt()).isEqualTo(lockedAt);
        assertThat(events).containsExactly(new AccountAuthenticationInvalidated(ACCOUNT_ID, lockedAt));
    }

    @Test
    void recordsNothingForAnIdempotentTransitionOrAMissingAccount() {
        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.of(account(AccountStatus.SUSPENDED)));
        service.suspendAccount(new AccountAdministrationCommand(ACCOUNT_ID, "admin-service", "request-6"));

        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.suspendAccount(
                new AccountAdministrationCommand(ACCOUNT_ID, "admin-service", "request-7")
        )).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_NOT_FOUND));

        verify(accounts, never()).recordStatusChange(any());
        verifyNoInteractions(securityEvents);
        assertThat(events).isEmpty();
    }

    @Test
    void findsOnlyAnActiveAccountForTokenIssuanceWithoutThrowing() {
        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));
        assertThat(service.findActiveAccount(ACCOUNT_ID)).hasValue(new AuthenticatedAccount(ACCOUNT_ID, NOW));

        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.of(account(AccountStatus.SUSPENDED)));
        assertThat(service.findActiveAccount(ACCOUNT_ID)).isEmpty();

        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.of(account(AccountStatus.DELETED)));
        assertThat(service.findActiveAccount(ACCOUNT_ID)).isEmpty();

        when(accounts.findLockedById(ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThat(service.findActiveAccount(ACCOUNT_ID)).isEmpty();
    }

    @Test
    void readsAnAccountSummaryOrReportsItMissing() {
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account(AccountStatus.ACTIVE)));
        assertThat(service.account(ACCOUNT_ID).status()).isEqualTo("active");

        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.account(ACCOUNT_ID))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_NOT_FOUND));
    }

    @Test
    void rejectsMissingOrOversizedAuditContextBeforeTouchingStorage() {
        assertThatThrownBy(() -> service.verifySignIn(verifyCommand(" ")))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_ADMIN_CONTEXT));
        assertThatThrownBy(() -> service.suspendAccount(new AccountAdministrationCommand(
                ACCOUNT_ID,
                "x".repeat(129),
                "request"
        ))).isInstanceOfSatisfying(IdentityFailure.class, failure ->
                assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_ADMIN_CONTEXT));
        verifyNoInteractions(accounts, loginIdentities, verificationGrants, securityEvents, operationGuards);
    }

    private void assertInvalidGrant() {
        assertThatThrownBy(() -> service.consumeSignInGrant(consumeCommand(AUDIENCE)))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.INVALID_GRANT));
    }

    private void existingIdentity() {
        when(loginIdentities.findByKindAndFingerprint(eq(IdentityKind.EMAIL), any()))
                .thenReturn(Optional.of(LoginIdentity.linkVerifiedEmail(UUID.randomUUID(), ACCOUNT_ID, NOW)));
    }

    private List<SecurityEvent> recordedEvents() {
        ArgumentCaptor<SecurityEvent> captor = ArgumentCaptor.forClass(SecurityEvent.class);
        verify(securityEvents, org.mockito.Mockito.atLeast(0)).append(captor.capture());
        return captor.getAllValues();
    }

    private List<SecurityEventType> recordedEventTypes() {
        return recordedEvents().stream().map(SecurityEvent::type).toList();
    }

    private static VerifyPasswordlessSignIn verifyCommand(String requestId) {
        return new VerifyPasswordlessSignIn(RecordingVerification.CHALLENGE_ID.toString(), "123456", requestId);
    }

    private static ConsumeSignInGrant consumeCommand(String audience) {
        return new ConsumeSignInGrant(GRANT, audience, "request-consume");
    }

    private static VerificationGrant grant(Instant expiresAt) {
        return VerificationGrant.issueSignIn(UUID.randomUUID(), ACCOUNT_ID, AUDIENCE, expiresAt, NOW.minusSeconds(60));
    }

    private static Account account(AccountStatus status) {
        return new Account(ACCOUNT_ID, status, NOW, 0, NOW, NOW, status == AccountStatus.DELETED ? NOW : null);
    }

    private static final class RecordingVerification implements EmailVerificationOperations {
        private static final UUID CHALLENGE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        private StartSignInEmailVerification signInStart;
        private VerifyEmailCode signInVerify;
        private VerificationFailure signInFailure;
        private boolean compatibilityStartCalled;

        void failSignInWith(VerificationFailure failure) {
            this.signInFailure = failure;
        }

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
            if (signInFailure != null) {
                throw signInFailure;
            }
            return new EmailVerified(CHALLENGE_ID, "sign_in", NOW, bytes(7));
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
