package com.vkplatform.iam.verification.infrastructure.persistence.repository;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationRepository;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailDeliveryStatus;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity_;
import com.vkplatform.iam.verification.infrastructure.persistence.mapper.EmailVerificationPersistenceMapper;
import com.vkplatform.iam.verification.infrastructure.persistence.specification.EmailVerificationChallengeSpecifications;
import com.vkplatform.iam.verification.infrastructure.persistence.specification.EmailVerificationChallengeUpdates;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JpaEmailVerificationRepository implements EmailVerificationRepository {
    private static final short START_OPERATION_GUARD_ID = 1;

    private final SpringDataEmailVerificationChallengeRepository challenges;
    private final SpringDataEmailVerificationOperationGuardRepository operationGuards;
    private final EmailVerificationPersistenceMapper mapper;

    JpaEmailVerificationRepository(
            SpringDataEmailVerificationChallengeRepository challenges,
            SpringDataEmailVerificationOperationGuardRepository operationGuards,
            EmailVerificationPersistenceMapper mapper
    ) {
        this.challenges = challenges;
        this.operationGuards = operationGuards;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void createPending(VerificationChallenge challenge, VerificationPolicy policy) {
        operationGuards.findById(START_OPERATION_GUARD_ID)
                .orElseThrow(() -> new IllegalStateException("email verification operation guard is missing"));

        Instant retryAt = challenge.createdAt();
        EmailVerificationChallengeEntity latest = challenges
                .findFirstByEmailFingerprintOrderByCreatedAtDesc(challenge.emailFingerprint())
                .orElse(null);
        if (latest != null) {
            retryAt = later(retryAt, latest.createdAt().plus(policy.resendCooldown()));
        }

        retryAt = later(retryAt, retryAtForEmail(challenge, policy));
        retryAt = later(retryAt, retryAtGlobally(challenge, policy));
        if (challenge.requesterFingerprint() != null) {
            retryAt = later(retryAt, retryAtForRequester(challenge, policy));
        }
        if (retryAt.isAfter(challenge.createdAt())) {
            throw VerificationFailure.rateLimited(retryAt);
        }

        challenges.update(EmailVerificationChallengeUpdates.invalidateActive(
                challenge.emailFingerprint(),
                challenge.purpose(),
                challenge.createdAt()
        ));

        challenges.save(mapper.toPendingEntity(challenge));
    }

    @Override
    @Transactional
    public void markSent(UUID id, Instant sentAt) {
        long changed = challenges.update(EmailVerificationChallengeUpdates.markSent(id, sentAt));
        requireSingleStateTransition(changed);
    }

    @Override
    @Transactional
    public void markFailed(UUID id) {
        long changed = challenges.update(EmailVerificationChallengeUpdates.markFailed(id));
        requireSingleStateTransition(changed);
    }

    @Override
    @Transactional
    public VerificationAttempt verify(UUID id, byte[] candidateDigest, Instant now) {
        EmailVerificationChallengeEntity challenge = challenges.findById(id).orElse(null);
        if (challenge == null
                || challenge.deliveryStatus() != EmailDeliveryStatus.SENT
                || challenge.invalidatedAt() != null
                || challenge.verifiedAt() != null
                || !now.isBefore(challenge.expiresAt())
                || challenge.attemptCount() >= challenge.maxAttempts()) {
            return VerificationAttempt.rejected();
        }

        if (!MessageDigest.isEqual(challenge.otpDigest(), candidateDigest)) {
            challenge.recordFailedAttempt();
            return VerificationAttempt.rejected();
        }

        challenge.markVerified(now);
        return VerificationAttempt.success(challenge.purpose());
    }

    private Instant retryAtForEmail(VerificationChallenge challenge, VerificationPolicy policy) {
        return retryAt(
                EmailVerificationChallengeSpecifications.hasEmailFingerprint(challenge.emailFingerprint())
                        .and(EmailVerificationChallengeSpecifications.startedAtOrAfter(
                                challenge.createdAt().minus(policy.emailWindow())
                        )),
                policy.maxStartsPerEmail(),
                policy.emailWindow(),
                challenge.createdAt()
        );
    }

    private Instant retryAtGlobally(VerificationChallenge challenge, VerificationPolicy policy) {
        return retryAt(
                EmailVerificationChallengeSpecifications.startedAtOrAfter(
                        challenge.createdAt().minus(policy.globalWindow())
                ),
                policy.maxGlobalStarts(),
                policy.globalWindow(),
                challenge.createdAt()
        );
    }

    private Instant retryAtForRequester(VerificationChallenge challenge, VerificationPolicy policy) {
        return retryAt(
                EmailVerificationChallengeSpecifications.hasRequesterFingerprint(challenge.requesterFingerprint())
                        .and(EmailVerificationChallengeSpecifications.startedAtOrAfter(
                                challenge.createdAt().minus(policy.requesterWindow())
                        )),
                policy.maxStartsPerRequester(),
                policy.requesterWindow(),
                challenge.createdAt()
        );
    }

    private Instant retryAt(
            Specification<EmailVerificationChallengeEntity> specification,
            int limit,
            Duration window,
            Instant now
    ) {
        List<EmailVerificationChallengeEntity> starts = challenges.findBy(
                specification,
                query -> query
                        .sortBy(Sort.by(Sort.Direction.DESC, EmailVerificationChallengeEntity_.CREATED_AT))
                        .limit(limit)
                        .all()
        );
        if (starts.size() < limit) {
            return now;
        }
        return starts.getLast().createdAt().plus(window);
    }

    private static Instant later(Instant first, Instant second) {
        return second.isAfter(first) ? second : first;
    }

    private static void requireSingleStateTransition(long changed) {
        if (changed != 1) {
            throw new IllegalStateException("email verification challenge state changed unexpectedly");
        }
    }
}
