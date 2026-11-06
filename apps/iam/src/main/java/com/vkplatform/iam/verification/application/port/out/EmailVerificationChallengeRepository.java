package com.vkplatform.iam.verification.application.port.out;

import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationPurpose;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationChallengeRepository {
    /** Finds the newest challenge for the email, whatever its purpose or state. */
    Optional<VerificationChallenge> findLatestByEmailFingerprint(byte[] emailFingerprint);

    /** Finds up to {@code limit} challenges for the email created at or after the given time, newest first. */
    List<VerificationChallenge> findNewestByEmailFingerprintCreatedSince(
            byte[] emailFingerprint,
            Instant createdSince,
            int limit
    );

    /** Finds up to {@code limit} challenges for the requester created at or after the given time, newest first. */
    List<VerificationChallenge> findNewestByRequesterFingerprintCreatedSince(
            byte[] requesterFingerprint,
            Instant createdSince,
            int limit
    );

    /** Finds up to {@code limit} challenges created at or after the given time, newest first. */
    List<VerificationChallenge> findNewestCreatedSince(Instant createdSince, int limit);

    /** Locks the challenge row until the caller's transaction ends. */
    Optional<VerificationChallenge> findLockedById(UUID id);

    void create(VerificationChallenge challenge);

    /**
     * Invalidates the email's challenges of the purpose that are neither verified nor invalidated
     * yet, with the supplied time.
     *
     * @return the number of challenges invalidated by this invocation
     */
    int invalidateActive(byte[] emailFingerprint, VerificationPurpose purpose, Instant invalidatedAt);

    /** Marks a pending, not invalidated challenge as sent. Fails when the challenge is in any other state. */
    void markSent(UUID id, Instant sentAt);

    /** Marks a pending challenge's delivery as failed. Fails when the challenge is in any other state. */
    void markFailed(UUID id);

    /** Stores a failed attempt the caller applied. The challenge row is locked before it is written. */
    void recordFailedAttempt(VerificationChallenge failed);

    /** Stores a verification the caller applied. The challenge row is locked before it is written. */
    void recordVerification(VerificationChallenge verified);

    /**
     * Deletes up to {@code limit} challenges, oldest first, that expired, were verified or were
     * invalidated before the cutoff, or whose delivery failed and that were created before it.
     *
     * @return the number of challenges deleted by this invocation
     */
    int deleteTerminalBefore(Instant cutoff, int limit);
}
