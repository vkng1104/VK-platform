package com.vkplatform.iam.verification.domain;

import java.time.Duration;

public record VerificationPolicy(
        Duration challengeTtl,
        Duration resendCooldown,
        Duration emailWindow,
        int maxStartsPerEmail,
        Duration requesterWindow,
        int maxStartsPerRequester,
        Duration globalWindow,
        int maxGlobalStarts,
        int maxAttempts
) {
    public static VerificationPolicy defaults() {
        return new VerificationPolicy(
                Duration.ofMinutes(5),
                Duration.ofMinutes(1),
                Duration.ofMinutes(15),
                3,
                Duration.ofHours(1),
                10,
                Duration.ofHours(24),
                100,
                5
        );
    }
}
