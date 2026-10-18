package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.identity.api.event.SecurityAuditEvent;
import com.vkplatform.iam.identity.api.event.SecurityAuditType;
import com.vkplatform.iam.identity.application.port.out.PasswordlessIdentityRepository;
import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.AccountStatus;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.identity.domain.IdentityKind;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.AccountEntity;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.LoginIdentityEntity;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.SecurityEventEntity;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.VerificationGrantEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public class JpaPasswordlessIdentityRepository implements PasswordlessIdentityRepository, SecurityAuditOperations {
    private static final int GUARD_COUNT = 64;

    private final SpringDataAccountRepository accounts;
    private final SpringDataLoginIdentityRepository identities;
    private final SpringDataVerificationGrantRepository grants;
    private final SpringDataSecurityEventRepository events;
    private final SpringDataIdentityOperationGuardRepository operationGuards;

    JpaPasswordlessIdentityRepository(
            SpringDataAccountRepository accounts,
            SpringDataLoginIdentityRepository identities,
            SpringDataVerificationGrantRepository grants,
            SpringDataSecurityEventRepository events,
            SpringDataIdentityOperationGuardRepository operationGuards
    ) {
        this.accounts = accounts;
        this.identities = identities;
        this.grants = grants;
        this.events = events;
        this.operationGuards = operationGuards;
    }

    @Override
    @Transactional
    public IssuedGrant issueSignInGrant(
            byte[] identityFingerprint,
            byte[] grantDigest,
            String audience,
            Instant expiresAt,
            Instant now,
            String requestId
    ) {
        lockIdentity(identityFingerprint);

        LoginIdentityEntity identity = identities
                .findByKindAndIdentifierFingerprint(IdentityKind.EMAIL, identityFingerprint)
                .orElse(null);
        AccountEntity accountEntity;
        if (identity == null) {
            Account account = Account.create(UUID.randomUUID(), now);
            accountEntity = accounts.save(new AccountEntity(account));
            identity = identities.save(new LoginIdentityEntity(
                    UUID.randomUUID(),
                    account.id(),
                    IdentityKind.EMAIL,
                    identityFingerprint,
                    now,
                    now
            ));
            appendEvent(SecurityAuditType.ACCOUNT_CREATED, account.id(), account.id(), null, requestId, now);
            appendEvent(
                    SecurityAuditType.LOGIN_IDENTITY_LINKED,
                    account.id(),
                    identity.id(),
                    null,
                    requestId,
                    now
            );
        } else {
            accountEntity = lockedAccount(identity.accountId());
        }

        Account account = accountEntity.toDomain();
        account.requireActive();

        VerificationGrantEntity grant = grants.save(new VerificationGrantEntity(
                UUID.randomUUID(),
                account.id(),
                identityFingerprint,
                grantDigest,
                audience,
                expiresAt,
                now
        ));
        appendEvent(
                SecurityAuditType.SIGN_IN_GRANT_ISSUED,
                account.id(),
                grant.id(),
                null,
                requestId,
                now
        );
        return new IssuedGrant(account.id(), expiresAt);
    }

    @Override
    @Transactional
    public Account consumeSignInGrant(
            byte[] grantDigest,
            String audience,
            Instant now,
            String requestId
    ) {
        VerificationGrantEntity grant = grants.findByGrantDigest(grantDigest)
                .orElseThrow(IdentityFailure::invalidGrant);
        if (!grant.canConsume(audience, now)) {
            throw IdentityFailure.invalidGrant();
        }

        Account account = lockedAccount(grant.accountId()).toDomain();
        account.requireActive();
        grant.consume(now);
        appendEvent(
                SecurityAuditType.SIGN_IN_GRANT_CONSUMED,
                account.id(),
                grant.id(),
                null,
                requestId,
                now
        );
        return account;
    }

    @Override
    @Transactional(readOnly = true)
    public Account findAccount(UUID accountId) {
        return accounts.findById(accountId)
                .map(AccountEntity::toDomain)
                .orElseThrow(IdentityFailure::accountNotFound);
    }

    @Override
    @Transactional
    public Account findAccountForTokenIssuance(UUID accountId) {
        return lockedAccount(accountId).toDomain();
    }

    @Override
    @Transactional
    public AccountStatusChange changeAccountStatus(
            UUID accountId,
            AccountStatus status,
            Instant now,
            String actorId,
            String requestId
    ) {
        AccountEntity entity = lockedAccount(accountId);
        Account current = entity.toDomain();
        Account changed = switch (status) {
            case ACTIVE -> current.reactivate(now);
            case SUSPENDED -> current.suspend(now);
            case DELETED -> current.delete(now);
            case RECOVERY_LOCKED -> throw new IllegalArgumentException("recovery lock requires a dedicated operation");
        };
        boolean statusChanged = changed.status() != current.status();
        if (statusChanged) {
            entity.apply(changed);
            appendEvent(eventFor(status), accountId, accountId, actorId, requestId, now);
        }
        return new AccountStatusChange(changed, statusChanged);
    }

    private AccountEntity lockedAccount(UUID accountId) {
        return accounts.findLockedById(accountId).orElseThrow(IdentityFailure::accountNotFound);
    }

    private void lockIdentity(byte[] fingerprint) {
        short guardId = (short) (Byte.toUnsignedInt(fingerprint[0]) % GUARD_COUNT);
        operationGuards.findById(guardId)
                .orElseThrow(() -> new IllegalStateException("identity operation guard is missing"));
    }

    private void appendEvent(
            SecurityAuditType type,
            UUID accountId,
            UUID targetId,
            String actorId,
            String requestId,
            Instant now
    ) {
        append(new SecurityAuditEvent(type, accountId, targetId, actorId, requestId, now));
    }

    @Override
    @Transactional
    public void append(SecurityAuditEvent event) {
        events.save(new SecurityEventEntity(
                UUID.randomUUID(),
                event.type(),
                event.accountId(),
                event.targetId(),
                event.actorId(),
                event.requestId(),
                event.occurredAt()
        ));
    }

    private static SecurityAuditType eventFor(AccountStatus status) {
        return switch (status) {
            case ACTIVE -> SecurityAuditType.ACCOUNT_REACTIVATED;
            case SUSPENDED -> SecurityAuditType.ACCOUNT_SUSPENDED;
            case DELETED -> SecurityAuditType.ACCOUNT_DELETED;
            case RECOVERY_LOCKED -> throw new IllegalArgumentException("unsupported account transition");
        };
    }
}
