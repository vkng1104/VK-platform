package com.vkplatform.iam.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityPolicyTest {
    @Test
    void validatesAudienceAndShortGrantLifetime() {
        assertThatThrownBy(() -> new IdentityPolicy("invalid audience", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityPolicy("vk-platform-web", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityPolicy("vk-platform-web", Duration.ofMinutes(16)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
