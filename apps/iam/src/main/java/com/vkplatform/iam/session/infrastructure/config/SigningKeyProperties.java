package com.vkplatform.iam.session.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("iam.signing-keys")
public record SigningKeyProperties(
        String protectionKeys,
        String protectionVersion,
        Duration publicationSafetyOverlap,
        boolean retirementEnabled,
        Duration retirementInitialDelay,
        Duration retirementInterval
) {
    public SigningKeyProperties {
        requirePositive(publicationSafetyOverlap, "IAM_SIGNING_KEY_PUBLICATION_SAFETY_OVERLAP");
        requirePositive(retirementInitialDelay, "IAM_SIGNING_KEY_RETIREMENT_INITIAL_DELAY");
        requirePositive(retirementInterval, "IAM_SIGNING_KEY_RETIREMENT_INTERVAL");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be greater than zero");
        }
    }
}
