package com.vkplatform.iam.verification.application.port.out;

import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.domain.VerificationPurpose;

import java.time.Instant;
import java.util.UUID;

public interface EmailVerificationRepository {
    void createPending(VerificationChallenge challenge, VerificationPolicy policy);

    void markSent(UUID id, Instant sentAt);

    void markFailed(UUID id);

    VerificationAttempt verify(UUID id, byte[] candidateDigest, Instant now);

    int deleteTerminalBefore(Instant cutoff, int batchSize);

    record VerificationAttempt(boolean verified, VerificationPurpose purpose) {
        public VerificationAttempt {
            if (verified != (purpose != null)) {
                throw new IllegalArgumentException("verified attempts require exactly one purpose");
            }
        }

        public static VerificationAttempt success(VerificationPurpose purpose) {
            return new VerificationAttempt(true, purpose);
        }

        public static VerificationAttempt rejected() {
            return new VerificationAttempt(false, null);
        }
    }
}
