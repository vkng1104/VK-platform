package com.vkplatform.iam.identity.infrastructure.persistence.entity;

import com.vkplatform.iam.identity.domain.GrantPurpose;
import com.vkplatform.iam.identity.infrastructure.persistence.converter.GrantPurposeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_grants", schema = "iam_identity")
public class VerificationGrantEntity extends AbstractUuidEntity {
    @Id
    private UUID id;

    @Convert(converter = GrantPurposeConverter.class)
    @Column(nullable = false)
    private GrantPurpose purpose;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "identity_fingerprint", nullable = false)
    private byte[] identityFingerprint;

    @Column(name = "grant_digest", nullable = false)
    private byte[] grantDigest;

    @Column(nullable = false, length = 128)
    private String audience;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected VerificationGrantEntity() {
    }

    public VerificationGrantEntity(
            UUID id,
            UUID accountId,
            byte[] identityFingerprint,
            byte[] grantDigest,
            String audience,
            Instant expiresAt,
            Instant createdAt
    ) {
        this.id = id;
        this.purpose = GrantPurpose.SIGN_IN;
        this.accountId = accountId;
        this.identityFingerprint = identityFingerprint.clone();
        this.grantDigest = grantDigest.clone();
        this.audience = audience;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public boolean canConsume(String expectedAudience, Instant now) {
        return purpose == GrantPurpose.SIGN_IN
                && audience.equals(expectedAudience)
                && consumedAt == null
                && now.isBefore(expiresAt);
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
