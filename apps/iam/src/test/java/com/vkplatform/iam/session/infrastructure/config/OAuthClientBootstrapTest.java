package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthClientBootstrapTest {
    private static final Instant NOW = Instant.parse("2026-10-11T09:00:00Z");

    @Test
    void registersExactPkceBffAndNarrowServiceClientsWithHashedSecrets() throws Exception {
        InMemoryClients clients = new InMemoryClients();
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        SessionProperties properties = properties("b".repeat(32), "s".repeat(32));
        OAuthClientBootstrap bootstrap = new OAuthClientBootstrap(
                clients,
                encoder,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        bootstrap.run(new DefaultApplicationArguments());
        bootstrap.run(new DefaultApplicationArguments());

        assertThat(clients.byClientId).hasSize(2);
        RegisteredClient bff = clients.findByClientId("vk-platform-bff");
        assertThat(bff.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
                AuthorizationGrantType.AUTHORIZATION_CODE,
                AuthorizationGrantType.REFRESH_TOKEN
        );
        assertThat(bff.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(bff.getRedirectUris())
                .containsExactly("https://web.example.test/api/auth/callback");
        assertThat(bff.getScopes()).containsExactly(OAuthClientBootstrap.API_ACCESS_SCOPE);
        assertThat(bff.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(bff.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(bff.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(5));
        assertThat(bff.getClientSecret()).isNotEqualTo(properties.bffClientSecret());
        assertThat(encoder.matches(properties.bffClientSecret(), bff.getClientSecret())).isTrue();

        RegisteredClient service = clients.findByClientId("vk-platform-api");
        assertThat(service.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(service.getRedirectUris()).isEmpty();
        assertThat(service.getScopes()).containsExactlyInAnyOrder(
                OAuthClientBootstrap.AUTHORIZATION_CHECK_SCOPE,
                OAuthClientBootstrap.RELATIONSHIP_WRITE_SCOPE
        );
        assertThat(service.getClientSecret()).isNotEqualTo(properties.serviceClientSecret());
        assertThat(encoder.matches(properties.serviceClientSecret(), service.getClientSecret())).isTrue();
    }

    @Test
    void refusesWeakBootstrapSecrets() {
        OAuthClientBootstrap bootstrap = new OAuthClientBootstrap(
                new InMemoryClients(),
                PasswordEncoderFactories.createDelegatingPasswordEncoder(),
                properties("too-short", "s".repeat(32)),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_BFF_CLIENT_SECRET");
    }

    private static SessionProperties properties(String bffSecret, String serviceSecret) {
        return new SessionProperties(
                true,
                URI.create("https://iam.example.test"),
                "vk-platform-api",
                "vk-platform-web",
                "vk-platform-bff",
                Duration.ofMinutes(5),
                Duration.ofDays(30),
                Duration.ofDays(30),
                URI.create("https://web.example.test/sign-in"),
                "bootstrap-key",
                "",
                "",
                "",
                bffSecret,
                URI.create("https://web.example.test/api/auth/callback"),
                "vk-platform-api",
                serviceSecret,
                "vk-platform-iam"
        );
    }

    private static final class InMemoryClients implements RegisteredClientRepository {
        private final Map<String, RegisteredClient> byId = new HashMap<>();
        private final Map<String, RegisteredClient> byClientId = new HashMap<>();

        @Override
        public void save(RegisteredClient registeredClient) {
            byId.put(registeredClient.getId(), registeredClient);
            byClientId.put(registeredClient.getClientId(), registeredClient);
        }

        @Override
        public RegisteredClient findById(String id) {
            return byId.get(id);
        }

        @Override
        public RegisteredClient findByClientId(String clientId) {
            return byClientId.get(clientId);
        }
    }
}
