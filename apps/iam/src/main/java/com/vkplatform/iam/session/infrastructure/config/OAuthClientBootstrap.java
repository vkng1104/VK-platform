package com.vkplatform.iam.session.infrastructure.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Clock;
import java.util.UUID;

final class OAuthClientBootstrap implements ApplicationRunner {
    static final String API_ACCESS_SCOPE = "api.access";
    static final String AUTHORIZATION_CHECK_SCOPE = "iam.authorization.check";
    static final String RELATIONSHIP_WRITE_SCOPE = "iam.relationship.write";

    private final RegisteredClientRepository clients;
    private final PasswordEncoder passwordEncoder;
    private final SessionProperties properties;
    private final Clock clock;

    OAuthClientBootstrap(
            RegisteredClientRepository clients,
            PasswordEncoder passwordEncoder,
            SessionProperties properties,
            Clock clock
    ) {
        this.clients = clients;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        requireSecret(properties.bffClientSecret(), "IAM_BFF_CLIENT_SECRET");
        requireSecret(properties.serviceClientSecret(), "IAM_SERVICE_CLIENT_SECRET");
        if (clients.findByClientId(properties.bffClientId()) == null) {
            clients.save(bffClient());
        }
        if (clients.findByClientId(properties.serviceClientId()) == null) {
            clients.save(serviceClient());
        }
    }

    private RegisteredClient bffClient() {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(properties.bffClientId())
                .clientIdIssuedAt(clock.instant())
                .clientSecret(passwordEncoder.encode(properties.bffClientSecret()))
                .clientName("VK Platform web BFF")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(properties.bffRedirectUri().toString())
                .scope(API_ACCESS_SCOPE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .refreshTokenTimeToLive(properties.refreshTokenTtl())
                        .reuseRefreshTokens(false)
                        .build())
                .build();
    }

    private RegisteredClient serviceClient() {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(properties.serviceClientId())
                .clientIdIssuedAt(clock.instant())
                .clientSecret(passwordEncoder.encode(properties.serviceClientSecret()))
                .clientName("VK Platform API service")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(AUTHORIZATION_CHECK_SCOPE)
                .scope(RELATIONSHIP_WRITE_SCOPE)
                .clientSettings(ClientSettings.builder().build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .build())
                .build();
    }

    private static void requireSecret(String secret, String property) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(property + " must contain at least 32 characters");
        }
    }
}
