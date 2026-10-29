package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthAuthorizationServerConfigurationTest {
    private final OAuthAuthorizationServerConfiguration configuration =
            new OAuthAuthorizationServerConfiguration();

    @Test
    void registersTheBffForOneTimeRefreshRotation() {
        RegisteredClientRepository repository = configuration.oauthRegisteredClients(
                sessionProperties(Duration.ofDays(30)),
                authorizationProperties(Duration.ofDays(30)),
                PasswordEncoderFactories.createDelegatingPasswordEncoder()
        );

        RegisteredClient client = repository.findByClientId("vk-platform-bff");

        assertThat(client).isNotNull();
        assertThat(client.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
                AuthorizationGrantType.AUTHORIZATION_CODE,
                AuthorizationGrantType.REFRESH_TOKEN
        );
        assertThat(client.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(client.getTokenSettings().getRefreshTokenTimeToLive())
                .isEqualTo(Duration.ofDays(30));
    }

    @Test
    void rejectsARefreshLifetimeBeyondTheAbsoluteSessionLifetime() {
        assertThatThrownBy(() -> configuration.oauthRegisteredClients(
                sessionProperties(Duration.ofDays(7)),
                authorizationProperties(Duration.ofDays(30)),
                PasswordEncoderFactories.createDelegatingPasswordEncoder()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_REFRESH_TOKEN_TTL");
    }

    private static SessionProperties sessionProperties(Duration absoluteTtl) {
        return new SessionProperties(
                true,
                "vk-platform-bff",
                "b".repeat(32),
                URI.create("https://web.example.test"),
                absoluteTtl
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
}
