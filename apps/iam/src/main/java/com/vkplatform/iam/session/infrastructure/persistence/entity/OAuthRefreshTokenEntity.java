package com.vkplatform.iam.session.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "oauth_refresh_tokens", schema = "iam_session")
public class OAuthRefreshTokenEntity extends AbstractSessionUuidEntity {
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

    public OAuthRefreshTokenEntity(
            UUID id,
            String authorizationId,
            UUID familyId,
            UUID parentId,
            byte[] tokenDigest,
            Instant issuedAt,
            Instant expiresAt
    ) {
        this.id = Objects.requireNonNull(id);
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new IllegalArgumentException("refresh-token authorization is invalid");
        }
        this.authorizationId = authorizationId;
        this.familyId = Objects.requireNonNull(familyId);
        this.parentId = parentId;
        if (tokenDigest == null || tokenDigest.length != 32) {
            throw new IllegalArgumentException("refresh-token digest is invalid");
        }
        this.tokenDigest = tokenDigest.clone();
        this.issuedAt = Objects.requireNonNull(issuedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("refresh-token expiry must follow issuance");
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String authorizationId() { return authorizationId; }
    public UUID familyId() { return familyId; }
    public UUID parentId() { return parentId; }
    public UUID replacementId() { return replacementId; }
    public byte[] tokenDigest() { return tokenDigest.clone(); }
    public Instant issuedAt() { return issuedAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant consumedAt() { return consumedAt; }
    public Instant reuseDetectedAt() { return reuseDetectedAt; }

    public boolean isActive(Instant now) {
        return consumedAt == null && reuseDetectedAt == null && now.isBefore(expiresAt);
    }

    public void consume(UUID replacement, Instant now) {
        Objects.requireNonNull(replacement);
        Objects.requireNonNull(now);
        if (consumedAt != null || replacementId != null || reuseDetectedAt != null) {
            throw new IllegalStateException("refresh token has already transitioned");
        }
        if (replacement.equals(id) || now.isBefore(issuedAt)) {
            throw new IllegalArgumentException("refresh-token consumption is invalid");
        }
        consumedAt = now;
        replacementId = replacement;
    }

    public void detectReuse(Instant now) {
        Objects.requireNonNull(now);
        if (consumedAt == null || replacementId == null || now.isBefore(consumedAt)) {
            throw new IllegalStateException("refresh-token reuse cannot precede consumption");
        }
        if (reuseDetectedAt == null) {
            reuseDetectedAt = now;
        }
    }
}
