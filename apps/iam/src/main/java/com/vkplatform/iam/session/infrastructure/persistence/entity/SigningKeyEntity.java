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
import java.util.Objects;

@Entity
@Table(name = "signing_keys", schema = "iam_session")
public class SigningKeyEntity {
    @Id
    @Column(length = 128)
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

    @Column(name = "compromised_at")
    private Instant compromisedAt;

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
        this.id = requireText(id, "signing-key ID", 128);
        this.algorithm = "RS256";
        this.publicKeyX509 = copy(publicKeyX509, "public key");
        this.encryptedPrivateKey = copy(encryptedPrivateKey, "encrypted private key");
        this.encryptionNonce = copy(encryptionNonce, "encryption nonce");
        this.protectionKeyVersion = requireText(
                protectionKeyVersion,
                "protection-key version",
                64
        );
        this.status = SigningKeyStatus.ACTIVE;
        this.createdAt = Objects.requireNonNull(now);
        this.activatedAt = now;
    }

    public String id() {
        return id;
    }

    public String algorithm() {
        return algorithm;
    }

    public byte[] publicKeyX509() {
        return publicKeyX509.clone();
    }

    public byte[] encryptedPrivateKey() {
        return encryptedPrivateKey.clone();
    }

    public byte[] encryptionNonce() {
        return encryptionNonce.clone();
    }

    public String protectionKeyVersion() {
        return protectionKeyVersion;
    }

    public SigningKeyStatus status() {
        return status;
    }

    public Instant activatedAt() {
        return activatedAt;
    }

    public Instant publishUntil() {
        return publishUntil;
    }

    public Instant retiredAt() {
        return retiredAt;
    }

    public Instant compromisedAt() {
        return compromisedAt;
    }

    public long version() {
        return version;
    }

    public void beginRetirement(Instant now, Instant publicationDeadline) {
        Objects.requireNonNull(now);
        Objects.requireNonNull(publicationDeadline);
        if (status != SigningKeyStatus.ACTIVE
                || now.isBefore(activatedAt)
                || !publicationDeadline.isAfter(now)) {
            throw new IllegalStateException("signing key cannot begin retirement");
        }
        status = SigningKeyStatus.RETIRING;
        publishUntil = publicationDeadline;
    }

    public boolean retire(Instant now) {
        Objects.requireNonNull(now);
        if (status == SigningKeyStatus.RETIRED || status == SigningKeyStatus.COMPROMISED) {
            return false;
        }
        if (status != SigningKeyStatus.RETIRING || now.isBefore(publishUntil)) {
            throw new IllegalStateException("signing key cannot be retired yet");
        }
        status = SigningKeyStatus.RETIRED;
        retiredAt = now;
        return true;
    }

    public boolean compromise(Instant now) {
        Objects.requireNonNull(now);
        if (status == SigningKeyStatus.COMPROMISED || status == SigningKeyStatus.RETIRED) {
            return false;
        }
        if ((status != SigningKeyStatus.ACTIVE && status != SigningKeyStatus.RETIRING)
                || now.isBefore(activatedAt)) {
            throw new IllegalStateException("signing key cannot be marked compromised");
        }
        status = SigningKeyStatus.COMPROMISED;
        publishUntil = now;
        compromisedAt = now;
        return true;
    }

    public void replaceProtection(String version, byte[] ciphertext, byte[] nonce) {
        encryptedPrivateKey = copy(ciphertext, "encrypted private key");
        encryptionNonce = copy(nonce, "encryption nonce");
        protectionKeyVersion = requireText(version, "protection-key version", 64);
    }

    private static byte[] copy(byte[] value, String name) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.clone();
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.strip();
    }
}
