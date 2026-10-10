package com.vkplatform.iam.verification.infrastructure.persistence.mapper;

import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity;
import org.springframework.stereotype.Component;

@Component
public final class EmailVerificationPersistenceMapper {
    public EmailVerificationChallengeEntity toPendingEntity(VerificationChallenge challenge) {
        return new EmailVerificationChallengeEntity(
                challenge.id(),
                challenge.purpose(),
                challenge.emailFingerprint(),
                challenge.requesterFingerprint(),
                challenge.otpDigest(),
                (short) challenge.maxAttempts(),
                challenge.expiresAt(),
                challenge.resendNotBefore(),
                challenge.createdAt()
        );
    }
}
