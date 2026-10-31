package com.vkplatform.iam.identity.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
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
import com.vkplatform.iam.verification.api.command.StartSignInEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class PasswordlessIdentityService implements PasswordlessIdentityOperations {
    private static final int MAX_CONTEXT_LENGTH = 128;

    private final EmailVerificationOperations verification;
    private final PasswordlessIdentityRepository repository;
    private final GrantTokenCodec grants;
    private final Clock clock;
    private final IdentityPolicy policy;
    private final ApplicationEventPublisher events;

    public PasswordlessIdentityService(
            EmailVerificationOperations verification,
            PasswordlessIdentityRepository repository,
            GrantTokenCodec grants,
            Clock clock,
            IdentityPolicy policy
    ) {
        this(verification, repository, grants, clock, policy, event -> { });
    }

    public PasswordlessIdentityService(
            EmailVerificationOperations verification,
            PasswordlessIdentityRepository repository,
            GrantTokenCodec grants,
            Clock clock,
            IdentityPolicy policy,
            ApplicationEventPublisher events
    ) {
        this.verification = Objects.requireNonNull(verification);
        this.repository = Objects.requireNonNull(repository);
        this.grants = Objects.requireNonNull(grants);
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
        this.events = Objects.requireNonNull(events);
    }

    @Override
    @Transactional
    public AuthenticatedAccount activeAccount(UUID accountId) {
        Account account = repository.findAccountForTokenIssuance(Objects.requireNonNull(accountId));
        account.requireActive();
        return new AuthenticatedAccount(account.id(), account.tokenNotBefore());
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

    @Override
    @Transactional
    public AuthenticationGrantIssued verifySignIn(VerifyPasswordlessSignIn command) {
        Objects.requireNonNull(command);
        String requestId = requireContext(command.requestId());
        EmailVerified verified = verification.verifySignIn(
                new VerifyEmailCode(command.challengeId(), command.code())
        );
        GrantTokenCodec.GeneratedGrant grant = grants.generate();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(policy.grantTtl());
        PasswordlessIdentityRepository.IssuedGrant issued = repository.issueSignInGrant(
                verified.identityFingerprint(),
                grant.digest(),
                policy.audience(),
                expiresAt,
                now,
                requestId
        );
        return new AuthenticationGrantIssued(
                issued.accountId(),
                grant.value(),
                policy.audience(),
                issued.expiresAt()
        );
    }

    @Override
    @Transactional
    public AuthenticatedAccount consumeSignInGrant(ConsumeSignInGrant command) {
        Objects.requireNonNull(command);
        if (!policy.audience().equals(command.audience())) {
            throw IdentityFailure.invalidAudience();
        }
        Account account = repository.consumeSignInGrant(
                grants.digest(command.grant()),
                policy.audience(),
                clock.instant(),
                requireContext(command.requestId())
        );
        return new AuthenticatedAccount(account.id(), account.tokenNotBefore());
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSummary account(UUID accountId) {
        return summary(repository.findAccount(Objects.requireNonNull(accountId)));
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

    private AccountSummary changeStatus(AccountAdministrationCommand command, AccountStatus status) {
        Objects.requireNonNull(command);
        if (command.accountId() == null) {
            throw IdentityFailure.invalidAdminContext();
        }
        Instant now = clock.instant();
        PasswordlessIdentityRepository.AccountStatusChange change = repository.changeAccountStatus(
                command.accountId(),
                status,
                now,
                requireContext(command.actorId()),
                requireContext(command.requestId())
        );
        if (change.changed()) {
            events.publishEvent(new AccountAuthenticationInvalidated(change.account().id(), now));
        }
        return summary(change.account());
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
