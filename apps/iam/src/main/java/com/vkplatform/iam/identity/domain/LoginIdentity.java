package com.vkplatform.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A verified way to sign in to an account. The identifier itself is never stored, only its
 * fingerprint, which the repository receives separately.
 */
public record LoginIdentity(
        UUID id,
        UUID accountId,
        IdentityKind kind,
        Instant verifiedAt,
        Instant createdAt
) {
    public LoginIdentity {
        Objects.requireNonNull(id);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(verifiedAt);
        Objects.requireNonNull(createdAt);
    }

    public static LoginIdentity linkVerifiedEmail(UUID id, UUID accountId, Instant now) {
        return new LoginIdentity(id, accountId, IdentityKind.EMAIL, now, now);
    }
}
