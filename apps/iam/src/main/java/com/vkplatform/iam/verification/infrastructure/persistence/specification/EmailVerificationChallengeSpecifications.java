package com.vkplatform.iam.verification.infrastructure.persistence.specification;

import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailDeliveryStatus;
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

    public static Specification<EmailVerificationChallengeEntity> terminalBefore(Instant cutoff) {
        return (root, query, builder) -> builder.or(
                builder.lessThan(root.get(EmailVerificationChallengeEntity_.expiresAt), cutoff),
                builder.lessThan(root.get(EmailVerificationChallengeEntity_.verifiedAt), cutoff),
                builder.lessThan(root.get(EmailVerificationChallengeEntity_.invalidatedAt), cutoff),
                builder.and(
                        builder.equal(
                                root.get(EmailVerificationChallengeEntity_.deliveryStatus),
                                EmailDeliveryStatus.FAILED
                        ),
                        builder.lessThan(root.get(EmailVerificationChallengeEntity_.createdAt), cutoff)
                )
        );
    }
}
