package com.vkplatform.iam.identity.infrastructure.persistence.entity;

import com.vkplatform.iam.identity.domain.GrantPurpose;
import com.vkplatform.iam.identity.domain.VerificationGrant;
import com.vkplatform.iam.identity.infrastructure.persistence.converter.GrantPurposeConverter;
import com.vkplatform.iam.platform.persistence.AbstractAssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_grants", schema = "iam_identity")
public class VerificationGrantEntity extends AbstractAssignedIdEntity<UUID> {
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

    public VerificationGrantEntity(VerificationGrant grant, byte[] identityFingerprint, byte[] grantDigest) {
        this.id = grant.id();
        this.purpose = grant.purpose();
        this.accountId = grant.accountId();
        this.identityFingerprint = identityFingerprint.clone();
        this.grantDigest = grantDigest.clone();
        this.audience = grant.audience();
        this.expiresAt = grant.expiresAt();
        this.consumedAt = grant.consumedAt();
        this.createdAt = grant.createdAt();
    }

    public VerificationGrant toDomain() {
        return new VerificationGrant(id, purpose, accountId, audience, expiresAt, consumedAt, createdAt);
    }

    /** Copies a consumption; everything else about a grant is fixed when it is issued. */
    public void apply(VerificationGrant grant) {
        if (!id.equals(grant.id())
                || purpose != grant.purpose()
                || !accountId.equals(grant.accountId())
                || !audience.equals(grant.audience())
                || !expiresAt.equals(grant.expiresAt())
                || !createdAt.equals(grant.createdAt())) {
            throw new IllegalArgumentException("verification grant identity cannot change");
        }
        consumedAt = grant.consumedAt();
    }

    @Override
    public UUID getId() {
        return id;
    }
}
