package com.vkplatform.iam.identity.infrastructure.persistence.entity;

import com.vkplatform.iam.identity.domain.IdentityKind;
import com.vkplatform.iam.identity.infrastructure.persistence.converter.IdentityKindConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "login_identities", schema = "iam_identity")
public class LoginIdentityEntity extends AbstractUuidEntity {
    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Convert(converter = IdentityKindConverter.class)
    @Column(nullable = false)
    private IdentityKind kind;

    @Column(name = "identifier_fingerprint", nullable = false)
    private byte[] identifierFingerprint;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoginIdentityEntity() {
    }

    public LoginIdentityEntity(
            UUID id,
            UUID accountId,
            IdentityKind kind,
            byte[] identifierFingerprint,
            Instant verifiedAt,
            Instant createdAt
    ) {
        this.id = id;
        this.accountId = accountId;
        this.kind = kind;
        this.identifierFingerprint = identifierFingerprint.clone();
        this.verifiedAt = verifiedAt;
        this.createdAt = createdAt;
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
