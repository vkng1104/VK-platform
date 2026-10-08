package com.vkplatform.iam.verification.infrastructure.persistence;

import com.vkplatform.iam.verification.domain.VerificationChallenge;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "email_verification_challenges")
class EmailVerificationChallengeEntity {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String purpose;

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

    @Column(name = "delivery_status", nullable = false)
    private String deliveryStatus;

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

    static EmailVerificationChallengeEntity pending(VerificationChallenge challenge) {
        EmailVerificationChallengeEntity entity = new EmailVerificationChallengeEntity();
        entity.id = challenge.id();
        entity.purpose = challenge.purpose().wireValue();
        entity.emailFingerprint = challenge.emailFingerprint().clone();
        entity.requesterFingerprint = challenge.requesterFingerprint() == null
                ? null
                : challenge.requesterFingerprint().clone();
        entity.otpDigest = challenge.otpDigest().clone();
        entity.attemptCount = 0;
        entity.maxAttempts = (short) challenge.maxAttempts();
        entity.expiresAt = challenge.expiresAt();
        entity.resendNotBefore = challenge.resendNotBefore();
        entity.deliveryStatus = "pending";
        entity.createdAt = challenge.createdAt();
        return entity;
    }

    byte[] otpDigest() {
        return otpDigest;
    }

    short attemptCount() {
        return attemptCount;
    }

    short maxAttempts() {
        return maxAttempts;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    String deliveryStatus() {
        return deliveryStatus;
    }

    Instant verifiedAt() {
        return verifiedAt;
    }

    Instant invalidatedAt() {
        return invalidatedAt;
    }

    void recordFailedAttempt() {
        attemptCount++;
    }

    void markVerified(Instant at) {
        verifiedAt = at;
    }
}
