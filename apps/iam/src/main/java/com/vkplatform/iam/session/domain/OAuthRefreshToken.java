package com.vkplatform.iam.session.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry in the refresh-token ledger. A family starts with a root token; each rotation consumes
 * the current token and issues a child in the same family. A consumed token that is presented
 * again is marked as reused.
 */
public record OAuthRefreshToken(
        UUID id,
        String authorizationId,
        UUID familyId,
        UUID parentId,
        Instant issuedAt,
        Instant expiresAt,
        UUID replacementId,
        Instant consumedAt,
        Instant reuseDetectedAt
) {
    public OAuthRefreshToken {
        Objects.requireNonNull(id);
        Objects.requireNonNull(familyId);
        Objects.requireNonNull(issuedAt);
        Objects.requireNonNull(expiresAt);
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new IllegalArgumentException("refresh-token authorization is invalid");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("refresh-token expiry must follow issuance");
        }
        if (id.equals(parentId) || id.equals(replacementId)) {
            throw new IllegalArgumentException("refresh-token lineage cannot point to itself");
        }
        if ((consumedAt == null) != (replacementId == null)) {
            throw new IllegalArgumentException("refresh-token consumption state is incomplete");
        }
        if (consumedAt != null && consumedAt.isBefore(issuedAt)) {
            throw new IllegalArgumentException("refresh-token consumption precedes issuance");
        }
        if (reuseDetectedAt != null && (consumedAt == null || reuseDetectedAt.isBefore(consumedAt))) {
            throw new IllegalArgumentException("refresh-token reuse cannot precede consumption");
        }
    }

    public static OAuthRefreshToken issue(
            UUID id,
            String authorizationId,
            UUID familyId,
            UUID parentId,
            Instant issuedAt,
            Instant expiresAt
    ) {
        return new OAuthRefreshToken(
                id,
                authorizationId,
                familyId,
                parentId,
                issuedAt,
                expiresAt,
                null,
                null,
                null
        );
    }

    public boolean isActive(Instant now) {
        return consumedAt == null && reuseDetectedAt == null && now.isBefore(expiresAt);
    }

    public OAuthRefreshToken consume(UUID replacement, Instant now) {
        Objects.requireNonNull(replacement);
        Objects.requireNonNull(now);
        if (consumedAt != null || replacementId != null || reuseDetectedAt != null) {
            throw new IllegalStateException("refresh token has already transitioned");
        }
        if (replacement.equals(id) || now.isBefore(issuedAt)) {
            throw new IllegalArgumentException("refresh-token consumption is invalid");
        }
        return new OAuthRefreshToken(
                id,
                authorizationId,
                familyId,
                parentId,
                issuedAt,
                expiresAt,
                replacement,
                now,
                null
        );
    }

    public OAuthRefreshToken detectReuse(Instant now) {
        Objects.requireNonNull(now);
        if (consumedAt == null || replacementId == null || now.isBefore(consumedAt)) {
            throw new IllegalStateException("refresh-token reuse cannot precede consumption");
        }
        if (reuseDetectedAt != null) {
            return this;
        }
        return new OAuthRefreshToken(
                id,
                authorizationId,
                familyId,
                parentId,
                issuedAt,
                expiresAt,
                replacementId,
                consumedAt,
                now
        );
    }
}
