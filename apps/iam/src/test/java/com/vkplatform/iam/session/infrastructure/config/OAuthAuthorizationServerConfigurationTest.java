package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthAuthorizationServerConfigurationTest {
    private final OAuthAuthorizationServerConfiguration configuration =
            new OAuthAuthorizationServerConfiguration();

    @Test
    void createsTheClientPasswordEncoderAfterValidatingTokenAndAudienceBoundaries() {
        var passwords = configuration.oauthClientPasswordEncoder(
                sessionProperties(Duration.ofDays(30)),
                authorizationProperties(Duration.ofDays(30)),
                clientProperties("vk-platform-services")
        );

        assertThat(passwords.matches("b".repeat(32), passwords.encode("b".repeat(32)))).isTrue();
    }

    @Test
    void rejectsARefreshLifetimeBeyondTheAbsoluteSessionLifetime() {
        assertThatThrownBy(() -> configuration.oauthClientPasswordEncoder(
                sessionProperties(Duration.ofDays(7)),
                authorizationProperties(Duration.ofDays(30)),
                clientProperties("vk-platform-services")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_REFRESH_TOKEN_TTL");
    }

    @Test
    void rejectsAServiceAudienceThatCouldBeConfusedWithUserTokens() {
        assertThatThrownBy(() -> configuration.oauthClientPasswordEncoder(
                sessionProperties(Duration.ofDays(30)),
                authorizationProperties(Duration.ofDays(30)),
                clientProperties("vk-platform-api")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_SERVICE_AUDIENCE");
    }

    private static SessionProperties sessionProperties(Duration absoluteTtl) {
        return new SessionProperties(
                true,
                "vk-platform-bff",
                "b".repeat(32),
                URI.create("https://web.example.test"),
                absoluteTtl,
                "VK_IAM_SESSION",
                true
        );
    }

    private static OAuthAuthorizationProperties authorizationProperties(Duration refreshTtl) {
        return new OAuthAuthorizationProperties(
                URI.create("https://iam.example.test"),
                "vk-platform-api",
                URI.create("https://web.example.test/sign-in"),
                URI.create("https://web.example.test/api/auth/callback"),
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                refreshTtl
        );
    }

    private static OAuthClientProperties clientProperties(String audience) {
        return new OAuthClientProperties(
                "vk-platform-service",
                "s".repeat(32),
                audience,
                "iam.authorization.check,iam.relationship.write",
                "",
                "",
                "",
                "",
                "",
                null
        );
    }
}
