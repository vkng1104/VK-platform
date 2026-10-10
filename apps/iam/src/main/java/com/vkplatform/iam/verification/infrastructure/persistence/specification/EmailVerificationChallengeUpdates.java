package com.vkplatform.iam.verification.infrastructure.persistence.specification;

import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity_;
import org.springframework.data.jpa.domain.UpdateSpecification;

import java.time.Instant;
import java.util.UUID;

public final class EmailVerificationChallengeUpdates {
    private EmailVerificationChallengeUpdates() {
    }

    public static UpdateSpecification<EmailVerificationChallengeEntity> invalidateActive(
            byte[] emailFingerprint,
            String purpose,
            Instant invalidatedAt
    ) {
        byte[] fingerprintCopy = emailFingerprint.clone();
        return (root, update, builder) -> {
            update.set(EmailVerificationChallengeEntity_.invalidatedAt, invalidatedAt);
            return builder.and(
                    builder.equal(root.get(EmailVerificationChallengeEntity_.emailFingerprint), fingerprintCopy),
                    builder.equal(root.get(EmailVerificationChallengeEntity_.purpose), purpose),
                    builder.isNull(root.get(EmailVerificationChallengeEntity_.verifiedAt)),
                    builder.isNull(root.get(EmailVerificationChallengeEntity_.invalidatedAt))
            );
        };
    }

    public static UpdateSpecification<EmailVerificationChallengeEntity> markSent(UUID id, Instant sentAt) {
        return (root, update, builder) -> {
            update.set(EmailVerificationChallengeEntity_.deliveryStatus, "sent");
            update.set(EmailVerificationChallengeEntity_.sentAt, sentAt);
            return builder.and(
                    builder.equal(root.get(EmailVerificationChallengeEntity_.id), id),
                    builder.equal(root.get(EmailVerificationChallengeEntity_.deliveryStatus), "pending"),
                    builder.isNull(root.get(EmailVerificationChallengeEntity_.invalidatedAt))
            );
        };
    }

    public static UpdateSpecification<EmailVerificationChallengeEntity> markFailed(UUID id) {
        return (root, update, builder) -> {
            update.set(EmailVerificationChallengeEntity_.deliveryStatus, "failed");
            return builder.and(
                    builder.equal(root.get(EmailVerificationChallengeEntity_.id), id),
                    builder.equal(root.get(EmailVerificationChallengeEntity_.deliveryStatus), "pending")
            );
        };
    }
}
