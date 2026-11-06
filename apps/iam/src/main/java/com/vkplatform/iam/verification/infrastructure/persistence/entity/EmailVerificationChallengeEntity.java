package com.vkplatform.iam.verification.infrastructure.persistence.entity;

import com.vkplatform.iam.platform.persistence.AbstractAssignedIdEntity;
import com.vkplatform.iam.verification.domain.EmailDeliveryStatus;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import com.vkplatform.iam.verification.infrastructure.persistence.converter.EmailDeliveryStatusConverter;
import com.vkplatform.iam.verification.infrastructure.persistence.converter.VerificationPurposeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "email_verification_challenges", schema = "iam_identity")
public class EmailVerificationChallengeEntity extends AbstractAssignedIdEntity<UUID> {
    @Id
    private UUID id;

    @Convert(converter = VerificationPurposeConverter.class)
    @Column(nullable = false)
    private VerificationPurpose purpose;

    @Column(name = "email_fingerprint", nullable = false)
    private byte[] emailFingerprint;

    @Column(name = "requester_fingerprint")
    private byte[] requesterFingerprint;

    @Column(name = "otp_digest", nullable = false)
    private byte[] otpDigest;

    @Column(name = "attempt_count", nullable = false)
    private short attemptCount;

    @Column(name = "max_attempts", nullable = false)
    private short maxAttempts;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resend_not_before", nullable = false)
    private Instant resendNotBefore;

    @Convert(converter = EmailDeliveryStatusConverter.class)
    @Column(name = "delivery_status", nullable = false)
    private EmailDeliveryStatus deliveryStatus;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EmailVerificationChallengeEntity() {
    }

    public EmailVerificationChallengeEntity(VerificationChallenge challenge) {
        Objects.requireNonNull(challenge);
        this.id = challenge.id();
        this.purpose = challenge.purpose();
        this.emailFingerprint = challenge.emailFingerprint();
        this.requesterFingerprint = challenge.requesterFingerprint();
        this.otpDigest = challenge.otpDigest();
        this.attemptCount = (short) challenge.attemptCount();
        this.maxAttempts = (short) challenge.maxAttempts();
        this.expiresAt = challenge.expiresAt();
        this.resendNotBefore = challenge.resendNotBefore();
        this.deliveryStatus = challenge.deliveryStatus();
        this.sentAt = challenge.sentAt();
        this.verifiedAt = challenge.verifiedAt();
        this.invalidatedAt = challenge.invalidatedAt();
        this.createdAt = challenge.createdAt();
    }

    public VerificationChallenge toDomain() {
        return new VerificationChallenge(
                id,
                purpose,
                emailFingerprint,
                requesterFingerprint,
                otpDigest,
                attemptCount,
                maxAttempts,
                expiresAt,
                resendNotBefore,
                deliveryStatus,
                sentAt,
                verifiedAt,
                invalidatedAt,
                createdAt
        );
    }

    /**
     * Copies a failed attempt or a verification the caller applied. The challenge's identity
     * cannot change, and its delivery and invalidation state change only through their own
     * updates.
     */
    public void apply(VerificationChallenge challenge) {
        if (!id.equals(challenge.id())
                || purpose != challenge.purpose()
                || maxAttempts != challenge.maxAttempts()
                || !expiresAt.equals(challenge.expiresAt())
                || !createdAt.equals(challenge.createdAt())) {
            throw new IllegalArgumentException("email verification challenge identity cannot change");
        }
        attemptCount = (short) challenge.attemptCount();
        verifiedAt = challenge.verifiedAt();
    }

    @Override
    public UUID getId() {
        return id;
    }
}
