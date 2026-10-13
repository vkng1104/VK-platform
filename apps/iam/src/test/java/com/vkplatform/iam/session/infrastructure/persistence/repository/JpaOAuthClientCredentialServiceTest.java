package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientEventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaOAuthClientCredentialServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:30:00Z");

    private JpaRegisteredClientRepository clients;
    private SpringDataOAuthClientEventRepository events;
    private PasswordEncoder encoder;
    private JpaOAuthClientCredentialService service;

    @BeforeEach
    void setUp() {
        clients = mock(JpaRegisteredClientRepository.class);
        events = mock(SpringDataOAuthClientEventRepository.class);
        encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        service = new JpaOAuthClientCredentialService(
                clients,
                events,
                encoder,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void rotatesToAHashAndEmitsAnAuditEventWithoutReturningTheCredential() {
        when(clients.findLockedByClientId("vk-platform-api")).thenReturn(client());
        String newSecret = "n".repeat(32);
        Instant expiresAt = NOW.plusSeconds(3600);

        var rotation = service.rotateSecret(new RotateOAuthClientSecret(
                " vk-platform-api ",
                newSecret,
                expiresAt
        ));

        ArgumentCaptor<RegisteredClient> saved = ArgumentCaptor.forClass(RegisteredClient.class);
        verify(clients).save(saved.capture());
        assertThat(saved.getValue().getClientSecret()).isNotEqualTo(newSecret);
        assertThat(encoder.matches(newSecret, saved.getValue().getClientSecret())).isTrue();
        assertThat(saved.getValue().getClientSecretExpiresAt()).isEqualTo(expiresAt);
        verify(events).save(any(OAuthClientEventEntity.class));
        assertThat(rotation.clientId()).isEqualTo("vk-platform-api");
        assertThat(rotation.rotatedAt()).isEqualTo(NOW);
        assertThat(rotation.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void rejectsWeakOrAlreadyExpiredCredentialsBeforeWriting() {
        assertThatThrownBy(() -> service.rotateSecret(new RotateOAuthClientSecret(
                "vk-platform-api",
                "too-short",
                NOW.plusSeconds(1)
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rotateSecret(new RotateOAuthClientSecret(
                "vk-platform-api",
                "n".repeat(32),
                NOW
        ))).isInstanceOf(IllegalArgumentException.class);

        verify(clients, never()).save(any());
        verify(events, never()).save(any());
    }

    private static RegisteredClient client() {
        return RegisteredClient.withId("service-registration")
                .clientId("vk-platform-api")
                .clientIdIssuedAt(NOW.minusSeconds(3600))
                .clientSecret("{noop}old-client-secret")
                .clientName("VK Platform API service")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("iam.authorization.check")
                .build();
    }
}
