package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IamOperationsPropertiesTest {
    @Test
    void acceptsBoundedRetentionAndOperatorConfiguration() {
        IamOperationsProperties properties = properties(Duration.ofDays(30), 100, 250);

        assertThat(properties.authorizationRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.cleanupBatchSize()).isEqualTo(100);
        assertThat(properties.operatorBatchSize()).isEqualTo(250);
    }

    @Test
    void rejectsNonPositiveRetentionAndOutOfRangeBatches() {
        assertThatThrownBy(() -> properties(Duration.ZERO, 100, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_AUTHORIZATION_RETENTION");
        assertThatThrownBy(() -> properties(Duration.ofDays(30), 0, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_CLEANUP_BATCH_SIZE");
        assertThatThrownBy(() -> properties(Duration.ofDays(30), 100, 1_001))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OPERATOR_BATCH_SIZE");
    }

    private static IamOperationsProperties properties(
            Duration authorizationRetention,
            int cleanupBatch,
            int operatorBatch
    ) {
        return new IamOperationsProperties(
                false,
                Duration.ofDays(90),
                authorizationRetention,
                Duration.ofDays(365),
                Duration.ofDays(90),
                cleanupBatch,
                Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                "",
                "",
                "",
                "",
                "",
                "",
                null,
                operatorBatch
        );
    }
}
