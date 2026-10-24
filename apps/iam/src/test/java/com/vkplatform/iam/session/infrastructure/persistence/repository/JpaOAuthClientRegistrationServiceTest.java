package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientOperationGuardEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.vkplatform.iam.session.SessionTestFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaOAuthClientRegistrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T09:00:00Z");

    private final Map<String, RegisteredClient> clientsById = new HashMap<>();
    private final Map<String, RegisteredClient> clientsByClientId = new HashMap<>();
    private SpringDataOAuthClientOperationGuardRepository guard;
    private JpaRegisteredClientRepository clients;
    private SpringDataOAuthClientEventRepository events;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        guard = mock(SpringDataOAuthClientOperationGuardRepository.class);
        clients = mock(JpaRegisteredClientRepository.class);
        events = mock(SpringDataOAuthClientEventRepository.class);
        encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        when(guard.findLockedById((short) 1))
                .thenReturn(Optional.of(mock(OAuthClientOperationGuardEntity.class)));
        when(clients.findLockedByClientId(any())).thenAnswer(invocation ->
                clientsByClientId.get(invocation.getArgument(0, String.class)));
        when(clients.findByClientId(any())).thenAnswer(invocation ->
                clientsByClientId.get(invocation.getArgument(0, String.class)));
        org.mockito.Mockito.doAnswer(invocation -> {
            RegisteredClient client = invocation.getArgument(0);
            clientsById.put(client.getId(), client);
            clientsByClientId.put(client.getClientId(), client);
            return null;
        }).when(clients).save(any());
    }

    @Test
    void explicitlyRegistersExactClientsAndIsIdempotent() {
        JpaOAuthClientRegistrationService service = service("b".repeat(32), "s".repeat(32));

        assertThat(service.reconcile(context())).isEqualTo(2);
        assertThat(service.reconcile(context())).isZero();

        RegisteredClient bff = clientsByClientId.get("vk-platform-bff");
        assertThat(bff.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
                AuthorizationGrantType.AUTHORIZATION_CODE,
                AuthorizationGrantType.REFRESH_TOKEN
        );
        assertThat(bff.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        assertThat(bff.getScopes()).containsExactly(JpaOAuthClientRegistrationService.API_ACCESS_SCOPE);
        assertThat(bff.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(bff.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(encoder.matches("b".repeat(32), bff.getClientSecret())).isTrue();

        RegisteredClient api = clientsByClientId.get("vk-platform-api");
        assertThat(api.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(api.getScopes()).containsExactlyInAnyOrder(
                JpaOAuthClientRegistrationService.AUTHORIZATION_CHECK_SCOPE,
                JpaOAuthClientRegistrationService.RELATIONSHIP_WRITE_SCOPE
        );
        assertThat(encoder.matches("s".repeat(32), api.getClientSecret())).isTrue();
        verify(clients, times(2)).save(any());
        verify(events, times(2)).save(any());
    }

    @Test
    void reconcilesConfigurationDriftWithoutReplacingRotatedCredentials() {
        String rotatedHash = encoder.encode("rotated-secret-value-that-is-long");
        RegisteredClient drifted = RegisteredClient.withId("bff-id")
                .clientId("vk-platform-bff")
                .clientIdIssuedAt(NOW.minusSeconds(600))
                .clientSecret(rotatedHash)
                .clientSecretExpiresAt(NOW.plusSeconds(3600))
                .clientName("stale name")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://stale.example.test/callback")
                .scope("stale.scope")
                .build();
        clientsByClientId.put(drifted.getClientId(), drifted);

        assertThat(service("bootstrap-secret-that-must-not-win", "s".repeat(32))
                .reconcile(context())).isEqualTo(2);

        RegisteredClient reconciled = clientsByClientId.get("vk-platform-bff");
        assertThat(reconciled.getId()).isEqualTo("bff-id");
        assertThat(reconciled.getClientSecret()).isEqualTo(rotatedHash);
        assertThat(reconciled.getClientSecretExpiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void refusesWeakInitialSecretsBeforeRegistration() {
        assertThatThrownBy(() -> service("too-short", "s".repeat(32)).reconcile(context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_BFF_CLIENT_SECRET");
    }

    @Test
    void existingClientsDoNotRequireBootstrapSecrets() {
        assertThat(service("b".repeat(32), "s".repeat(32)).reconcile(context())).isEqualTo(2);

        assertThat(service("", "").reconcile(context())).isZero();

        verify(clients, times(2)).save(any());
    }

    private JpaOAuthClientRegistrationService service(String bffSecret, String serviceSecret) {
        return new JpaOAuthClientRegistrationService(
                guard,
                clients,
                events,
                encoder,
                properties(bffSecret, serviceSecret),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static OperatorContext context() {
        return new OperatorContext("security-operator", "client-reconcile-1");
    }
}
