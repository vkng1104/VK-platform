package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyPropertiesTest {
    @Test
    void acceptsPositiveLifecycleDurations() {
        SigningKeyProperties properties = properties(Duration.ofMinutes(1));

        assertThat(properties.publicationSafetyOverlap()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void rejectsInvalidDurations() {
        assertThatThrownBy(() -> properties(Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLICATION_SAFETY_OVERLAP");
    }

    private static SigningKeyProperties properties(Duration overlap) {
        return new SigningKeyProperties(
                "",
                "",
                overlap,
                true,
                Duration.ofMinutes(1),
                Duration.ofMinutes(5)
        );
    }
}
