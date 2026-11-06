package com.vkplatform.iam.identity.application.service;

import com.vkplatform.iam.identity.api.AccountAuthenticationInvalidated;
import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
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
import com.vkplatform.iam.verification.api.command.StartSignInEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class PasswordlessIdentityService implements PasswordlessIdentityOperations {
    private static final int MAX_CONTEXT_LENGTH = 128;

    private final EmailVerificationOperations verification;
    private final AccountRepository accounts;
    private final LoginIdentityRepository loginIdentities;
    private final VerificationGrantRepository verificationGrants;
    private final SecurityEventRepository securityEvents;
    private final IdentityOperationGuardRepository operationGuards;
    private final GrantTokenCodec grantTokens;
    private final Clock clock;
    private final IdentityPolicy policy;
    private final ApplicationEventPublisher events;

    public PasswordlessIdentityService(
            EmailVerificationOperations verification,
            AccountRepository accounts,
            LoginIdentityRepository loginIdentities,
            VerificationGrantRepository verificationGrants,
            SecurityEventRepository securityEvents,
            IdentityOperationGuardRepository operationGuards,
            GrantTokenCodec grantTokens,
            Clock clock,
            IdentityPolicy policy,
            ApplicationEventPublisher events
    ) {
        this.verification = Objects.requireNonNull(verification);
        this.accounts = Objects.requireNonNull(accounts);
        this.loginIdentities = Objects.requireNonNull(loginIdentities);
        this.verificationGrants = Objects.requireNonNull(verificationGrants);
        this.securityEvents = Objects.requireNonNull(securityEvents);
        this.operationGuards = Objects.requireNonNull(operationGuards);
        this.grantTokens = Objects.requireNonNull(grantTokens);
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
        this.events = Objects.requireNonNull(events);
    }

    @Override
    @Transactional
    public Optional<AuthenticatedAccount> findActiveAccount(UUID accountId) {
        return accounts.findLockedById(Objects.requireNonNull(accountId))
                .filter(account -> account.status() == AccountStatus.ACTIVE)
                .map(account -> new AuthenticatedAccount(account.id(), account.tokenNotBefore()));
    }

    @Override
    public AuthenticationChallengeStarted startSignIn(StartPasswordlessSignIn command) {
        Objects.requireNonNull(command);
        VerificationChallengeStarted started = verification.startSignIn(
                new StartSignInEmailVerification(command.email(), command.requesterAddress())
        );
        return new AuthenticationChallengeStarted(
                started.id(),
                started.maskedEmail(),
                started.expiresAt(),
                started.resendAfter()
        );
    }

    /**
     * Verifies the email code, then resolves or creates the account for that email and issues a
     * one-time sign-in grant. The identifier's guard row is locked first, so two sign-ins for the
     * same email cannot both create an account.
     */
    @Override
    @Transactional
    public AuthenticationGrantIssued verifySignIn(VerifyPasswordlessSignIn command) {
        Objects.requireNonNull(command);
        String requestId = requireContext(command.requestId());
        EmailVerified verified = verification.verifySignIn(
                new VerifyEmailCode(command.challengeId(), command.code())
        );
        GrantTokenCodec.GeneratedGrant grantToken = grantTokens.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(policy.grantTtl());
        byte[] fingerprint = verified.identityFingerprint();

        operationGuards.lockForIdentifier(fingerprint);
        Account account = loginIdentities.findByKindAndFingerprint(IdentityKind.EMAIL, fingerprint)
                .map(identity -> accounts.findLockedById(identity.accountId())
                        .orElseThrow(IdentityFailure::accountNotFound))
                .orElseGet(() -> createAccountForEmail(fingerprint, requestId, now));
        account.requireActive();

        VerificationGrant grant = VerificationGrant.issueSignIn(
                UUID.randomUUID(),
                account.id(),
                policy.audience(),
                expiresAt,
                now
        );
        verificationGrants.create(grant, fingerprint, grantToken.digest());
        recordEvent(SecurityEventType.SIGN_IN_GRANT_ISSUED, account.id(), grant.id(), null, requestId, now);
        return new AuthenticationGrantIssued(account.id(), grantToken.value(), policy.audience(), expiresAt);
    }

    @Override
    @Transactional
    public AuthenticatedAccount consumeSignInGrant(ConsumeSignInGrant command) {
        Objects.requireNonNull(command);
        if (!policy.audience().equals(command.audience())) {
            throw IdentityFailure.invalidAudience();
        }
        byte[] grantDigest = grantTokens.digest(command.grant());
        Instant now = clock.instant();
        String requestId = requireContext(command.requestId());
        VerificationGrant grant = verificationGrants.findLockedByDigest(grantDigest)
                .orElseThrow(IdentityFailure::invalidGrant);
        if (!grant.canConsume(policy.audience(), now)) {
            throw IdentityFailure.invalidGrant();
        }

        Account account = accounts.findLockedById(grant.accountId()).orElseThrow(IdentityFailure::accountNotFound);
        account.requireActive();
        verificationGrants.recordConsumption(grant.consume(now));
        recordEvent(SecurityEventType.SIGN_IN_GRANT_CONSUMED, account.id(), grant.id(), null, requestId, now);
        return new AuthenticatedAccount(account.id(), account.tokenNotBefore());
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSummary account(UUID accountId) {
        return summary(accounts.findById(Objects.requireNonNull(accountId))
                .orElseThrow(IdentityFailure::accountNotFound));
    }

    @Override
    @Transactional
    public AccountSummary suspendAccount(AccountAdministrationCommand command) {
        return changeStatus(command, AccountStatus.SUSPENDED);
    }

    @Override
    @Transactional
    public AccountSummary reactivateAccount(AccountAdministrationCommand command) {
        return changeStatus(command, AccountStatus.ACTIVE);
    }

    @Override
    @Transactional
    public AccountSummary deleteAccount(AccountAdministrationCommand command) {
        return changeStatus(command, AccountStatus.DELETED);
    }

    private Account createAccountForEmail(byte[] fingerprint, String requestId, Instant now) {
        Account account = Account.create(UUID.randomUUID(), now);
        accounts.create(account);
        LoginIdentity identity = LoginIdentity.linkVerifiedEmail(UUID.randomUUID(), account.id(), now);
        loginIdentities.create(identity, fingerprint);
        recordEvent(SecurityEventType.ACCOUNT_CREATED, account.id(), account.id(), null, requestId, now);
        recordEvent(SecurityEventType.LOGIN_IDENTITY_LINKED, account.id(), identity.id(), null, requestId, now);
        return account;
    }

    private AccountSummary changeStatus(AccountAdministrationCommand command, AccountStatus status) {
        Objects.requireNonNull(command);
        if (command.accountId() == null) {
            throw IdentityFailure.invalidAdminContext();
        }
        String actorId = requireContext(command.actorId());
        String requestId = requireContext(command.requestId());
        Account current = accounts.findLockedById(command.accountId())
                .orElseThrow(IdentityFailure::accountNotFound);
        Instant transitionAt = clock.instant();
        Account changed = switch (status) {
            case ACTIVE -> current.reactivate(transitionAt);
            case SUSPENDED -> current.suspend(transitionAt);
            case DELETED -> current.delete(transitionAt);
            case RECOVERY_LOCKED -> throw new IllegalArgumentException("recovery lock requires a dedicated operation");
        };
        if (changed.status() != current.status()) {
            accounts.recordStatusChange(changed);
            recordEvent(eventFor(changed.status()), changed.id(), changed.id(), actorId, requestId, transitionAt);
            events.publishEvent(new AccountAuthenticationInvalidated(changed.id(), transitionAt));
        }
        return summary(changed);
    }

    private void recordEvent(
            SecurityEventType type,
            UUID accountId,
            UUID targetId,
            String actorId,
            String requestId,
            Instant occurredAt
    ) {
        securityEvents.append(new SecurityEvent(
                UUID.randomUUID(),
                type,
                accountId,
                targetId,
                actorId,
                requestId,
                occurredAt
        ));
    }

    private static SecurityEventType eventFor(AccountStatus status) {
        return switch (status) {
            case ACTIVE -> SecurityEventType.ACCOUNT_REACTIVATED;
            case SUSPENDED -> SecurityEventType.ACCOUNT_SUSPENDED;
            case DELETED -> SecurityEventType.ACCOUNT_DELETED;
            case RECOVERY_LOCKED -> throw new IllegalArgumentException("unsupported account transition");
        };
    }

    private static AccountSummary summary(Account account) {
        return new AccountSummary(
                account.id(),
                account.status().wireValue(),
                account.tokenNotBefore(),
                account.createdAt(),
                account.updatedAt(),
                account.deletedAt()
        );
    }

    private static String requireContext(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_CONTEXT_LENGTH) {
            throw IdentityFailure.invalidAdminContext();
        }
        return value.strip();
    }
}
