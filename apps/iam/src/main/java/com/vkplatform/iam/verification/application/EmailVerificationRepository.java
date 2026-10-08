package com.vkplatform.iam.verification.application;

import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationPolicy;

import java.time.Instant;
import java.util.UUID;

public interface EmailVerificationRepository {
    void createPending(VerificationChallenge challenge, VerificationPolicy policy);

    void markSent(UUID id, Instant sentAt);

    void markFailed(UUID id);

    VerificationAttempt verify(UUID id, byte[] candidateDigest, Instant now);

    record VerificationAttempt(boolean verified) {
        public static VerificationAttempt success() {
            return new VerificationAttempt(true);
        }

        public static VerificationAttempt rejected() {
            return new VerificationAttempt(false);
        }
    }
}
