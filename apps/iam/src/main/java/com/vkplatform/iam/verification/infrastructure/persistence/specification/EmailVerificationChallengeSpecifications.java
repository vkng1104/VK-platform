package com.vkplatform.iam.verification.infrastructure.persistence.specification;

import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity_;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

public final class EmailVerificationChallengeSpecifications {
    private EmailVerificationChallengeSpecifications() {
    }

    public static Specification<EmailVerificationChallengeEntity> startedAtOrAfter(Instant windowStart) {
        return (root, query, builder) -> builder.greaterThanOrEqualTo(
                root.get(EmailVerificationChallengeEntity_.createdAt),
                windowStart
        );
    }

    public static Specification<EmailVerificationChallengeEntity> hasEmailFingerprint(byte[] fingerprint) {
        byte[] fingerprintCopy = fingerprint.clone();
        return (root, query, builder) -> builder.equal(
                root.get(EmailVerificationChallengeEntity_.emailFingerprint),
                fingerprintCopy
        );
    }

    public static Specification<EmailVerificationChallengeEntity> hasRequesterFingerprint(byte[] fingerprint) {
        byte[] fingerprintCopy = fingerprint.clone();
        return (root, query, builder) -> builder.equal(
                root.get(EmailVerificationChallengeEntity_.requesterFingerprint),
                fingerprintCopy
        );
    }
}
