package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthAuthorizationPropertiesTest {
    @Test
    void acceptsTheMinimalAuthorizationCodeConfiguration() {
        OAuthAuthorizationProperties properties = properties(
                URI.create("https://iam.example.test"),
                Duration.ofMinutes(5)
        );

        assertThat(properties.audience()).isEqualTo("vk-platform-api");
        assertThat(properties.bffRedirectUri()).isEqualTo(
                URI.create("https://web.example.test/api/auth/callback")
        );
        assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void rejectsNonOriginIssuerAndNonPositiveLifetimes() {
        assertThatThrownBy(() -> properties(
                URI.create("https://iam.example.test/oauth"),
                Duration.ofMinutes(5)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_ISSUER");
        assertThatThrownBy(() -> properties(
                URI.create("https://iam.example.test"),
                Duration.ZERO
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_ACCESS_TOKEN_TTL");
        assertThatThrownBy(() -> new OAuthAuthorizationProperties(
                URI.create("https://iam.example.test"),
                "vk-platform-api",
                URI.create("https://web.example.test/sign-in"),
                URI.create("https://web.example.test/api/auth/callback"),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                Duration.ZERO
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_REFRESH_TOKEN_TTL");
    }

    private static OAuthAuthorizationProperties properties(URI issuer, Duration accessTokenTtl) {
        return new OAuthAuthorizationProperties(
                issuer,
                "vk-platform-api",
                URI.create("https://web.example.test/sign-in"),
                URI.create("https://web.example.test/api/auth/callback"),
                Duration.ofMinutes(5),
                accessTokenTtl,
                Duration.ofDays(30)
        );
    }
}
