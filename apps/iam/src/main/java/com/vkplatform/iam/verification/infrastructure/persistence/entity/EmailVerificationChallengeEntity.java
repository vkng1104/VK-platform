package com.vkplatform.iam.verification.infrastructure.persistence.entity;

import com.vkplatform.iam.verification.domain.VerificationPurpose;
import com.vkplatform.iam.verification.infrastructure.persistence.converter.EmailDeliveryStatusConverter;
import com.vkplatform.iam.verification.infrastructure.persistence.converter.VerificationPurposeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification_challenges")
public class EmailVerificationChallengeEntity {
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

    public EmailVerificationChallengeEntity(
            UUID id,
            VerificationPurpose purpose,
            byte[] emailFingerprint,
            byte[] requesterFingerprint,
            byte[] otpDigest,
            short maxAttempts,
            Instant expiresAt,
            Instant resendNotBefore,
            Instant createdAt
    ) {
        this.id = id;
        this.purpose = purpose;
        this.emailFingerprint = emailFingerprint.clone();
        this.requesterFingerprint = requesterFingerprint == null ? null : requesterFingerprint.clone();
        this.otpDigest = otpDigest.clone();
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
        this.expiresAt = expiresAt;
        this.resendNotBefore = resendNotBefore;
        this.deliveryStatus = EmailDeliveryStatus.PENDING;
        this.createdAt = createdAt;
    }

    public VerificationPurpose purpose() {
        return purpose;
    }

    public byte[] otpDigest() {
        return otpDigest.clone();
    }

    public short attemptCount() {
        return attemptCount;
    }

    public short maxAttempts() {
        return maxAttempts;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public EmailDeliveryStatus deliveryStatus() {
        return deliveryStatus;
    }

    public Instant verifiedAt() {
        return verifiedAt;
    }

    public Instant invalidatedAt() {
        return invalidatedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void recordFailedAttempt() {
        attemptCount++;
    }

    public void markVerified(Instant at) {
        verifiedAt = at;
    }
}
