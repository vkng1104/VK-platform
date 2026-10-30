package com.vkplatform.iam.session.domain;

import java.time.Duration;
import java.util.Objects;

public record IamRetentionPolicy(
        Duration sessionRetention,
        Duration authorizationRetention,
        Duration auditRetention,
        Duration signingKeyRetention,
        int batchSize
) {
    public IamRetentionPolicy {
        requirePositive(sessionRetention, "session retention");
        requirePositive(authorizationRetention, "authorization retention");
        requirePositive(auditRetention, "audit retention");
        requirePositive(signingKeyRetention, "signing-key retention");
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("retention batch size must be between 1 and 1000");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
