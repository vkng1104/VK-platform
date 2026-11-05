package com.vkplatform.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A one-time sign-in grant. Only its digest is stored, so the record carries no secret; the
 * repository receives the identity fingerprint and grant digest separately when it creates one.
 */
public record VerificationGrant(
        UUID id,
        GrantPurpose purpose,
        UUID accountId,
        String audience,
        Instant expiresAt,
        Instant consumedAt,
        Instant createdAt
) {
    public VerificationGrant {
        Objects.requireNonNull(id);
        Objects.requireNonNull(purpose);
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(audience);
        Objects.requireNonNull(expiresAt);
        Objects.requireNonNull(createdAt);
    }

    public static VerificationGrant issueSignIn(
            UUID id,
            UUID accountId,
            String audience,
            Instant expiresAt,
            Instant createdAt
    ) {
        return new VerificationGrant(id, GrantPurpose.SIGN_IN, accountId, audience, expiresAt, null, createdAt);
    }

    public boolean canConsume(String expectedAudience, Instant now) {
        return purpose == GrantPurpose.SIGN_IN
                && audience.equals(expectedAudience)
                && consumedAt == null
                && now.isBefore(expiresAt);
    }

    public VerificationGrant consume(Instant now) {
        return new VerificationGrant(id, purpose, accountId, audience, expiresAt, Objects.requireNonNull(now), createdAt);
    }
}
