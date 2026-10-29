package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyPropertiesTest {
    @Test
    void acceptsBoundedOperationalConfiguration() {
        SigningKeyProperties properties = properties(Duration.ofMinutes(1), 100);

        assertThat(properties.publicationSafetyOverlap()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.operatorBatchSize()).isEqualTo(100);
    }

    @Test
    void rejectsInvalidDurationsAndBatchSizes() {
        assertThatThrownBy(() -> properties(Duration.ZERO, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLICATION_SAFETY_OVERLAP");
        assertThatThrownBy(() -> properties(Duration.ofMinutes(1), 1_001))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPERATOR_BATCH_SIZE");
    }

    private static SigningKeyProperties properties(Duration overlap, int batchSize) {
        return new SigningKeyProperties(
                "",
                "",
                overlap,
                true,
                Duration.ofMinutes(1),
                Duration.ofMinutes(5),
                "",
                "",
                "",
                "",
                batchSize
        );
    }
}
