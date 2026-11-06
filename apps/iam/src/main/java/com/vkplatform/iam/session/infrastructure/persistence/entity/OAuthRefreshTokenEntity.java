package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.platform.persistence.AbstractAssignedIdEntity;
import com.vkplatform.iam.session.domain.OAuthRefreshToken;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "oauth_refresh_tokens", schema = "iam_session")
public class OAuthRefreshTokenEntity extends AbstractAssignedIdEntity<UUID> {
    @Id
    private UUID id;

    @Column(name = "authorization_id", nullable = false, length = 100)
    private String authorizationId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "replacement_id")
    private UUID replacementId;

    @Column(name = "token_digest", nullable = false)
    private byte[] tokenDigest;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "reuse_detected_at")
    private Instant reuseDetectedAt;

    protected OAuthRefreshTokenEntity() {
    }

    public OAuthRefreshTokenEntity(OAuthRefreshToken token, byte[] tokenDigest) {
        Objects.requireNonNull(token);
        if (tokenDigest == null || tokenDigest.length != 32) {
            throw new IllegalArgumentException("refresh-token digest is invalid");
        }
        this.id = token.id();
        this.authorizationId = token.authorizationId();
        this.familyId = token.familyId();
        this.parentId = token.parentId();
        this.tokenDigest = tokenDigest.clone();
        this.issuedAt = token.issuedAt();
        this.expiresAt = token.expiresAt();
        apply(token);
    }

    public OAuthRefreshToken toDomain() {
        return new OAuthRefreshToken(
                id,
                authorizationId,
                familyId,
                parentId,
                issuedAt,
                expiresAt,
                replacementId,
                consumedAt,
                reuseDetectedAt
        );
    }

    /** Copies a consumption or reuse transition; the token's identity and lineage cannot change. */
    public void apply(OAuthRefreshToken token) {
        if (!id.equals(token.id())
                || !authorizationId.equals(token.authorizationId())
                || !familyId.equals(token.familyId())
                || !Objects.equals(parentId, token.parentId())
                || !issuedAt.equals(token.issuedAt())
                || !expiresAt.equals(token.expiresAt())) {
            throw new IllegalArgumentException("refresh-token identity cannot change");
        }
        replacementId = token.replacementId();
        consumedAt = token.consumedAt();
        reuseDetectedAt = token.reuseDetectedAt();
    }

    @Override
    public UUID getId() {
        return id;
    }

    public byte[] tokenDigest() {
        return tokenDigest.clone();
    }
}
