package com.vkplatform.iam.session.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.Instant;

@ConfigurationProperties("iam.operations")
public record IamOperationsProperties(
        boolean cleanupEnabled,
        Duration sessionRetention,
        Duration authorizationRetention,
        Duration auditRetention,
        Duration signingKeyRetention,
        int cleanupBatchSize,
        Duration cleanupInitialDelay,
        Duration cleanupInterval,
        String operatorOperation,
        String operatorActor,
        String operatorOperationId,
        String operatorKeyId,
        String operatorClientId,
        String operatorClientSecret,
        Instant operatorClientSecretExpiresAt,
        int operatorBatchSize
) {
    public IamOperationsProperties {
        requirePositive(sessionRetention, "IAM_SESSION_RETENTION");
        requirePositive(authorizationRetention, "IAM_AUTHORIZATION_RETENTION");
        requirePositive(auditRetention, "IAM_SECURITY_AUDIT_RETENTION");
        requirePositive(signingKeyRetention, "IAM_SIGNING_KEY_RETENTION");
        requirePositive(cleanupInitialDelay, "IAM_CLEANUP_INITIAL_DELAY");
        requirePositive(cleanupInterval, "IAM_CLEANUP_INTERVAL");
        requireBatch(cleanupBatchSize, "IAM_CLEANUP_BATCH_SIZE");
        requireBatch(operatorBatchSize, "IAM_OPERATOR_BATCH_SIZE");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be greater than zero");
        }
    }

    private static void requireBatch(int value, String name) {
        if (value < 1 || value > 1_000) {
            throw new IllegalStateException(name + " must be between 1 and 1000");
        }
    }
}
