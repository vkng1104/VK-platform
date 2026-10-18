package com.vkplatform.iam.identity.application.port.out;

import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.AccountStatus;

import java.time.Instant;
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

    Account findAccountForTokenIssuance(UUID accountId);

    AccountStatusChange changeAccountStatus(
            UUID accountId,
            AccountStatus status,
            Instant now,
            String actorId,
            String requestId
    );

    record IssuedGrant(UUID accountId, Instant expiresAt) {
    }

    record AccountStatusChange(Account account, boolean changed) {
    }
}
