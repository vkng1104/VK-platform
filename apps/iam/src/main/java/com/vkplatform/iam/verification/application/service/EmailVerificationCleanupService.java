package com.vkplatform.iam.verification.application.service;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class EmailVerificationCleanupService {
    private final EmailVerificationRepository repository;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    public EmailVerificationCleanupService(
            EmailVerificationRepository repository,
            Clock clock,
            Duration retention,
            int batchSize
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
        this.retention = requirePositive(retention, "email verification retention");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("email verification cleanup batch size must be positive");
        }
        this.batchSize = batchSize;
    }

    public int cleanupOnce() {
        Instant cutoff = clock.instant().minus(retention);
        return repository.deleteTerminalBefore(cutoff, batchSize);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
