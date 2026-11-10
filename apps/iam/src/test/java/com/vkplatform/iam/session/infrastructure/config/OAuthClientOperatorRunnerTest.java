package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.OAuthClientOperations;
import com.vkplatform.iam.session.api.OAuthClientRegistrationOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthClientOperatorRunnerTest {
    @Test
    void dispatchesExplicitReconciliation() {
        OAuthClientProperties properties = mock(OAuthClientProperties.class);
        OAuthClientRegistrationOperations registrations = mock(
                OAuthClientRegistrationOperations.class
        );
        OAuthClientOperations credentials = mock(OAuthClientOperations.class);
        when(properties.operatorOperation()).thenReturn("reconcile");
        when(properties.operatorActor()).thenReturn("security-operator");
        when(properties.operatorOperationId()).thenReturn("reconcile-oauth-1");

        new OAuthClientOperatorRunner(properties, registrations, credentials)
                .run(new DefaultApplicationArguments());

        verify(registrations).reconcile(new OperatorContext(
                "security-operator",
                "reconcile-oauth-1"
        ));
        verify(credentials, never()).rotateSecret(any());
    }

    @Test
    void dispatchesExplicitCredentialRotationAndDoesNothingByDefault() {
        OAuthClientProperties properties = mock(OAuthClientProperties.class);
        OAuthClientRegistrationOperations registrations = mock(
                OAuthClientRegistrationOperations.class
        );
        OAuthClientOperations credentials = mock(OAuthClientOperations.class);
        OAuthClientOperatorRunner runner = new OAuthClientOperatorRunner(
                properties,
                registrations,
                credentials
        );
        when(properties.operatorOperation()).thenReturn("");
        runner.run(new DefaultApplicationArguments());
        verify(registrations, never()).reconcile(any());

        Instant expiresAt = Instant.parse("2026-11-01T00:00:00Z");
        when(properties.operatorOperation()).thenReturn("rotate-secret");
        when(properties.operatorActor()).thenReturn("security-operator");
        when(properties.operatorOperationId()).thenReturn("rotate-oauth-1");
        when(properties.operatorClientId()).thenReturn("vk-platform-service");
        when(properties.operatorClientSecret()).thenReturn("n".repeat(32));
        when(properties.operatorClientSecretExpiresAt()).thenReturn(expiresAt);

        runner.run(new DefaultApplicationArguments());

        ArgumentCaptor<RotateOAuthClientSecret> command = ArgumentCaptor.forClass(
                RotateOAuthClientSecret.class
        );
        verify(credentials).rotateSecret(command.capture());
        assertThat(command.getValue()).isEqualTo(new RotateOAuthClientSecret(
                "vk-platform-service",
                "n".repeat(32),
                expiresAt,
                new OperatorContext("security-operator", "rotate-oauth-1")
        ));
    }

    @Test
    void rejectsUnknownOperationsBeforeMutation() {
        OAuthClientProperties properties = mock(OAuthClientProperties.class);
        OAuthClientRegistrationOperations registrations = mock(
                OAuthClientRegistrationOperations.class
        );
        OAuthClientOperations credentials = mock(OAuthClientOperations.class);
        when(properties.operatorOperation()).thenReturn("unknown");
        when(properties.operatorActor()).thenReturn("security-operator");
        when(properties.operatorOperationId()).thenReturn("unknown-oauth-1");

        assertThatThrownBy(() -> new OAuthClientOperatorRunner(
                properties,
                registrations,
                credentials
        ).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported");
        verify(registrations, never()).reconcile(any());
        verify(credentials, never()).rotateSecret(any());
    }
}
