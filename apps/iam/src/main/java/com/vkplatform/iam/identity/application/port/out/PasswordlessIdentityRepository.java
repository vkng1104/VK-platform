package com.vkplatform.iam.identity.application.port.out;

import com.vkplatform.iam.identity.domain.Account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordlessIdentityRepository {
    IssuedGrant issueSignInGrant(
            byte[] identityFingerprint,
            byte[] grantDigest,
            String audience,
            Instant expiresAt,
            Instant now,
            String requestId
    );

    Account consumeSignInGrant(
            byte[] grantDigest,
            String audience,
            Instant now,
            String requestId
    );

    Account findAccount(UUID accountId);

    Optional<Account> findAccountForTokenIssuance(UUID accountId);

    Account findAccountForStatusChange(UUID accountId);

    void recordAccountStatusChange(
            Account changed,
            String actorId,
            String requestId,
            Instant transitionAt
    );

    record IssuedGrant(UUID accountId, Instant expiresAt) {
    }
}
