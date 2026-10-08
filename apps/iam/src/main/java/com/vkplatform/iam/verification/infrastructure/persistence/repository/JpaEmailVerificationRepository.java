package com.vkplatform.iam.verification.infrastructure.persistence.repository;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationRepository;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailVerificationChallengeEntity;
import com.vkplatform.iam.verification.infrastructure.persistence.mapper.EmailVerificationPersistenceMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JpaEmailVerificationRepository implements EmailVerificationRepository {
    private static final long START_ADVISORY_LOCK_ID = 836_472_901L;

    private final EntityManager entityManager;
    private final EmailVerificationPersistenceMapper mapper;

    public JpaEmailVerificationRepository(
            EntityManager entityManager,
            EmailVerificationPersistenceMapper mapper
    ) {
        this.entityManager = entityManager;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void createPending(VerificationChallenge challenge, VerificationPolicy policy) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:lockId)")
                .setParameter("lockId", START_ADVISORY_LOCK_ID)
                .getSingleResult();

        Instant retryAt = challenge.createdAt();
        List<Instant> latest = entityManager.createQuery("""
                        SELECT challenge.createdAt
                        FROM EmailVerificationChallengeEntity challenge
                        WHERE challenge.emailFingerprint = :fingerprint
                        ORDER BY challenge.createdAt DESC
                        """, Instant.class)
                .setParameter("fingerprint", challenge.emailFingerprint())
                .setMaxResults(1)
                .getResultList();
        if (!latest.isEmpty()) {
            retryAt = later(retryAt, latest.getFirst().plus(policy.resendCooldown()));
        }

        retryAt = later(retryAt, retryAtForEmail(challenge, policy));
        retryAt = later(retryAt, retryAtGlobally(challenge, policy));
        if (challenge.requesterFingerprint() != null) {
            retryAt = later(retryAt, retryAtForRequester(challenge, policy));
        }
        if (retryAt.isAfter(challenge.createdAt())) {
            throw VerificationFailure.rateLimited(retryAt);
        }

        entityManager.createQuery("""
                        UPDATE EmailVerificationChallengeEntity challenge
                        SET challenge.invalidatedAt = :now
                        WHERE challenge.emailFingerprint = :fingerprint
                          AND challenge.purpose = :purpose
                          AND challenge.verifiedAt IS NULL
                          AND challenge.invalidatedAt IS NULL
                        """)
                .setParameter("now", challenge.createdAt())
                .setParameter("fingerprint", challenge.emailFingerprint())
                .setParameter("purpose", challenge.purpose().wireValue())
                .executeUpdate();

        entityManager.persist(mapper.toPendingEntity(challenge));
    }

    @Override
    @Transactional
    public void markSent(UUID id, Instant sentAt) {
        int changed = entityManager.createQuery("""
                        UPDATE EmailVerificationChallengeEntity challenge
                        SET challenge.deliveryStatus = 'sent', challenge.sentAt = :sentAt
                        WHERE challenge.id = :id
                          AND challenge.deliveryStatus = 'pending'
                          AND challenge.invalidatedAt IS NULL
                        """)
                .setParameter("id", id)
                .setParameter("sentAt", sentAt)
                .executeUpdate();
        requireSingleStateTransition(changed);
    }

    @Override
    @Transactional
    public void markFailed(UUID id) {
        int changed = entityManager.createQuery("""
                        UPDATE EmailVerificationChallengeEntity challenge
                        SET challenge.deliveryStatus = 'failed'
                        WHERE challenge.id = :id AND challenge.deliveryStatus = 'pending'
                        """)
                .setParameter("id", id)
                .executeUpdate();
        requireSingleStateTransition(changed);
    }

    @Override
    @Transactional
    public VerificationAttempt verify(UUID id, byte[] candidateDigest, Instant now) {
        EmailVerificationChallengeEntity challenge = entityManager.find(
                EmailVerificationChallengeEntity.class,
                id,
                LockModeType.PESSIMISTIC_WRITE
        );
        if (challenge == null
                || !"sent".equals(challenge.deliveryStatus())
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
        return VerificationAttempt.success(VerificationPurpose.fromStoredValue(challenge.purpose()));
    }

    private Instant retryAtForEmail(VerificationChallenge challenge, VerificationPolicy policy) {
        return retryAt(
                entityManager.createQuery("""
                                SELECT item.createdAt
                                FROM EmailVerificationChallengeEntity item
                                WHERE item.emailFingerprint = :fingerprint
                                  AND item.createdAt >= :windowStart
                                ORDER BY item.createdAt
                                """, Instant.class)
                        .setParameter("fingerprint", challenge.emailFingerprint())
                        .setParameter("windowStart", challenge.createdAt().minus(policy.emailWindow()))
                        .getResultList(),
                policy.maxStartsPerEmail(),
                policy.emailWindow(),
                challenge.createdAt()
        );
    }

    private Instant retryAtGlobally(VerificationChallenge challenge, VerificationPolicy policy) {
        return retryAt(
                entityManager.createQuery("""
                                SELECT item.createdAt
                                FROM EmailVerificationChallengeEntity item
                                WHERE item.createdAt >= :windowStart
                                ORDER BY item.createdAt
                                """, Instant.class)
                        .setParameter("windowStart", challenge.createdAt().minus(policy.globalWindow()))
                        .getResultList(),
                policy.maxGlobalStarts(),
                policy.globalWindow(),
                challenge.createdAt()
        );
    }

    private Instant retryAtForRequester(VerificationChallenge challenge, VerificationPolicy policy) {
        return retryAt(
                entityManager.createQuery("""
                                SELECT item.createdAt
                                FROM EmailVerificationChallengeEntity item
                                WHERE item.requesterFingerprint = :fingerprint
                                  AND item.createdAt >= :windowStart
                                ORDER BY item.createdAt
                                """, Instant.class)
                        .setParameter("fingerprint", challenge.requesterFingerprint())
                        .setParameter("windowStart", challenge.createdAt().minus(policy.requesterWindow()))
                        .getResultList(),
                policy.maxStartsPerRequester(),
                policy.requesterWindow(),
                challenge.createdAt()
        );
    }

    private static Instant retryAt(List<Instant> starts, int limit, Duration window, Instant now) {
        if (starts.size() < limit) {
            return now;
        }
        return starts.get(starts.size() - limit).plus(window);
    }

    private static Instant later(Instant first, Instant second) {
        return second.isAfter(first) ? second : first;
    }

    private static void requireSingleStateTransition(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("email verification challenge state changed unexpectedly");
        }
    }
}
