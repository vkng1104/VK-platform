package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.session.domain.SigningKeyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "signing_keys", schema = "iam_session")
public class SigningKeyEntity {
    @Id
    private String id;

    @Column(nullable = false, length = 16)
    private String algorithm;

    @Column(name = "public_key_x509", nullable = false)
    private byte[] publicKeyX509;

    @Column(name = "encrypted_private_key", nullable = false)
    private byte[] encryptedPrivateKey;

    @Column(name = "encryption_nonce", nullable = false)
    private byte[] encryptionNonce;

    @Column(name = "protection_key_version", nullable = false, length = 64)
    private String protectionKeyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SigningKeyStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "publish_until")
    private Instant publishUntil;

    @Column(name = "retired_at")
    private Instant retiredAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected SigningKeyEntity() {
    }

    public SigningKeyEntity(
            String id,
            byte[] publicKeyX509,
            byte[] encryptedPrivateKey,
            byte[] encryptionNonce,
            String protectionKeyVersion,
            Instant now
    ) {
        this.id = id;
        this.algorithm = "RS256";
        this.publicKeyX509 = publicKeyX509.clone();
        this.encryptedPrivateKey = encryptedPrivateKey.clone();
        this.encryptionNonce = encryptionNonce.clone();
        this.protectionKeyVersion = protectionKeyVersion;
        this.status = SigningKeyStatus.ACTIVE;
        this.createdAt = now;
        this.activatedAt = now;
    }

    public String id() { return id; }
    public String algorithm() { return algorithm; }
    public byte[] publicKeyX509() { return publicKeyX509.clone(); }
    public byte[] encryptedPrivateKey() { return encryptedPrivateKey.clone(); }
    public byte[] encryptionNonce() { return encryptionNonce.clone(); }
    public String protectionKeyVersion() { return protectionKeyVersion; }
    public SigningKeyStatus status() { return status; }
    public Instant activatedAt() { return activatedAt; }
    public Instant publishUntil() { return publishUntil; }
    public Instant retiredAt() { return retiredAt; }
    public long version() { return version; }

    public void beginRetirement(Instant publishUntil) {
        if (status != SigningKeyStatus.ACTIVE || !publishUntil.isAfter(activatedAt)) {
            throw new IllegalStateException("signing key cannot begin retirement");
        }
        status = SigningKeyStatus.RETIRING;
        this.publishUntil = publishUntil;
    }

    public void retire(Instant now) {
        if (status != SigningKeyStatus.RETIRING || now.isBefore(publishUntil)) {
            throw new IllegalStateException("signing key cannot be retired yet");
        }
        status = SigningKeyStatus.RETIRED;
        retiredAt = now;
    }

    public void compromise(Instant now) {
        if ((status != SigningKeyStatus.ACTIVE && status != SigningKeyStatus.RETIRING)
                || now.isBefore(activatedAt)) {
            throw new IllegalStateException("signing key cannot be marked compromised");
        }
        status = SigningKeyStatus.COMPROMISED;
        publishUntil = publishUntil == null || publishUntil.isAfter(now) ? now : publishUntil;
        retiredAt = now;
    }

    public void replaceProtection(String version, byte[] ciphertext, byte[] nonce) {
        this.encryptedPrivateKey = ciphertext.clone();
        this.encryptionNonce = nonce.clone();
        this.protectionKeyVersion = version;
    }
}
