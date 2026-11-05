package com.vkplatform.iam.verification.infrastructure.persistence.repository;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationChallengeRepository;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity_;
import com.vkplatform.iam.verification.infrastructure.persistence.specification.EmailVerificationChallengeSpecifications;
import com.vkplatform.iam.verification.infrastructure.persistence.specification.EmailVerificationChallengeUpdates;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaEmailVerificationChallengeRepository implements EmailVerificationChallengeRepository {
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, EmailVerificationChallengeEntity_.CREATED_AT);
    private static final Sort CLEANUP_ORDER = Sort.by(
            Sort.Order.asc(EmailVerificationChallengeEntity_.CREATED_AT),
            Sort.Order.asc(EmailVerificationChallengeEntity_.ID)
    );

    private final SpringDataEmailVerificationChallengeRepository challenges;

    JpaEmailVerificationChallengeRepository(SpringDataEmailVerificationChallengeRepository challenges) {
        this.challenges = challenges;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VerificationChallenge> findLatestByEmailFingerprint(byte[] emailFingerprint) {
        return challenges.findFirstByEmailFingerprintOrderByCreatedAtDesc(emailFingerprint)
                .map(EmailVerificationChallengeEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VerificationChallenge> findNewestByEmailFingerprintCreatedSince(
            byte[] emailFingerprint,
            Instant createdSince,
            int limit
    ) {
        return findNewest(
                EmailVerificationChallengeSpecifications.hasEmailFingerprint(emailFingerprint)
                        .and(EmailVerificationChallengeSpecifications.startedAtOrAfter(createdSince)),
                limit
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<VerificationChallenge> findNewestByRequesterFingerprintCreatedSince(
            byte[] requesterFingerprint,
            Instant createdSince,
            int limit
    ) {
        return findNewest(
                EmailVerificationChallengeSpecifications.hasRequesterFingerprint(requesterFingerprint)
                        .and(EmailVerificationChallengeSpecifications.startedAtOrAfter(createdSince)),
                limit
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<VerificationChallenge> findNewestCreatedSince(Instant createdSince, int limit) {
        return findNewest(EmailVerificationChallengeSpecifications.startedAtOrAfter(createdSince), limit);
    }

    @Override
    @Transactional
    public Optional<VerificationChallenge> findLockedById(UUID id) {
        return challenges.findById(id).map(EmailVerificationChallengeEntity::toDomain);
    }

    @Override
    @Transactional
    public void create(VerificationChallenge challenge) {
        challenges.save(new EmailVerificationChallengeEntity(challenge));
    }

    @Override
    @Transactional
    public int invalidateActive(byte[] emailFingerprint, VerificationPurpose purpose, Instant invalidatedAt) {
        return Math.toIntExact(challenges.update(EmailVerificationChallengeUpdates.invalidateActive(
                emailFingerprint,
                purpose,
                invalidatedAt
        )));
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
    public void recordFailedAttempt(VerificationChallenge failed) {
        applyLocked(failed);
    }

    @Override
    @Transactional
    public void recordVerification(VerificationChallenge verified) {
        applyLocked(verified);
    }

    @Override
    @Transactional
    public int deleteTerminalBefore(Instant cutoff, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("email verification cleanup batch size must be positive");
        }

        List<EmailVerificationChallengeEntity> candidates = challenges.findBy(
                EmailVerificationChallengeSpecifications.terminalBefore(cutoff),
                query -> query
                        .sortBy(CLEANUP_ORDER)
                        .limit(limit)
                        .all()
        );
        if (candidates.isEmpty()) {
            return 0;
        }

        challenges.deleteAllInBatch(candidates);
        return candidates.size();
    }

    private List<VerificationChallenge> findNewest(
            Specification<EmailVerificationChallengeEntity> specification,
            int limit
    ) {
        return challenges.findBy(
                        specification,
                        query -> query
                                .sortBy(NEWEST_FIRST)
                                .limit(limit)
                                .all()
                ).stream()
                .map(EmailVerificationChallengeEntity::toDomain)
                .toList();
    }

    /** {@code findById} takes the row lock; a row this transaction already locked is not read again. */
    private void applyLocked(VerificationChallenge challenge) {
        challenges.findById(challenge.id())
                .orElseThrow(() -> new IllegalStateException("email verification challenge does not exist"))
                .apply(challenge);
    }

    private static void requireSingleStateTransition(long changed) {
        if (changed != 1) {
            throw new IllegalStateException("email verification challenge state changed unexpectedly");
        }
    }
}
