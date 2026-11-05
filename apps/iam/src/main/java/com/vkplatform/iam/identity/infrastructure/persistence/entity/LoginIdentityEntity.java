package com.vkplatform.iam.identity.infrastructure.persistence.entity;

import com.vkplatform.iam.identity.domain.IdentityKind;
import com.vkplatform.iam.identity.domain.LoginIdentity;
import com.vkplatform.iam.identity.infrastructure.persistence.converter.IdentityKindConverter;
import com.vkplatform.iam.platform.persistence.AbstractAssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "login_identities", schema = "iam_identity")
public class LoginIdentityEntity extends AbstractAssignedIdEntity<UUID> {
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

    public LoginIdentityEntity(LoginIdentity identity, byte[] identifierFingerprint) {
        this.id = identity.id();
        this.accountId = identity.accountId();
        this.kind = identity.kind();
        this.identifierFingerprint = identifierFingerprint.clone();
        this.verifiedAt = identity.verifiedAt();
        this.createdAt = identity.createdAt();
    }

    public LoginIdentity toDomain() {
        return new LoginIdentity(id, accountId, kind, verifiedAt, createdAt);
    }

    @Override
    public UUID getId() {
        return id;
    }
}
