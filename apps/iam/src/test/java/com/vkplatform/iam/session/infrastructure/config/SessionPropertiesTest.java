package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionPropertiesTest {
    @Test
    void validatesUrisAndBoundedOperationalBatchSizesWhenEnabled() {
        assertThatThrownBy(() -> properties(URI.create("/relative-origin"), Duration.ofMinutes(5), 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_BFF_ORIGIN");
        assertThatThrownBy(() -> properties(URI.create("https://web.example.test"), Duration.ZERO, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_JWKS_CACHE_TTL");
        assertThatThrownBy(() -> properties(URI.create("https://web.example.test"), Duration.ofMinutes(5), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_SESSION_CLEANUP_BATCH_SIZE");
        assertThatThrownBy(() -> properties(
                URI.create("https://web.example.test"),
                Duration.ofMinutes(5),
                1_001
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_SESSION_CLEANUP_BATCH_SIZE");
    }

    private static SessionProperties properties(
            URI bffOrigin,
            Duration jwksCacheTtl,
            int cleanupBatchSize
    ) {
        return new SessionProperties(
                true,
                URI.create("https://iam.example.test"),
                "vk-platform-api",
                "vk-platform-web",
                "vk-platform-bff",
                Duration.ofMinutes(5),
                jwksCacheTtl,
                Duration.ofDays(30),
                Duration.ofDays(30),
                URI.create("https://web.example.test/sign-in"),
                bffOrigin,
                "bootstrap-key",
                "",
                "",
                Base64.getEncoder().encodeToString(new byte[32]),
                "v1",
                "",
                "b".repeat(32),
                URI.create("https://web.example.test/api/auth/callback"),
                "vk-platform-api",
                "s".repeat(32),
                "vk-platform-iam",
                "VK_IAM_SESSION",
                true,
                true,
                Duration.ofDays(30),
                Duration.ofDays(30),
                Duration.ofDays(90),
                cleanupBatchSize,
                "",
                "operator",
                "operation",
                "",
                "",
                "",
                null,
                100
        );
    }
}
