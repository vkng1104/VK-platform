package com.vkplatform.iam.verification.application.service;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationChallengeRepository;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationOperationGuardRepository;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Deletes terminal challenges once they are older than the retention. Each run deletes one
 * bounded batch in one transaction that takes the operation guard first, so cleanup workers and
 * rate-limited starts never overlap.
 */
public final class EmailVerificationCleanupService {
    private final EmailVerificationChallengeRepository challenges;
    private final EmailVerificationOperationGuardRepository operationGuards;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    public EmailVerificationCleanupService(
            EmailVerificationChallengeRepository challenges,
            EmailVerificationOperationGuardRepository operationGuards,
            TransactionOperations transactions,
            Clock clock,
            Duration retention,
            int batchSize
    ) {
        this.challenges = Objects.requireNonNull(challenges);
        this.operationGuards = Objects.requireNonNull(operationGuards);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        this.retention = requirePositive(retention, "email verification retention");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("email verification cleanup batch size must be positive");
        }
        this.batchSize = batchSize;
    }

    public int cleanupOnce() {
        Instant cutoff = clock.instant().minus(retention);
        Integer deleted = transactions.execute(status -> {
            operationGuards.lock();
            return challenges.deleteTerminalBefore(cutoff, batchSize);
        });
        return Objects.requireNonNull(deleted);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
