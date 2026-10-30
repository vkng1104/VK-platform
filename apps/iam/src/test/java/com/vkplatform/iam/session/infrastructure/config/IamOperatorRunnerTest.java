package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.IamMaintenanceOperations;
import com.vkplatform.iam.session.api.OAuthClientOperations;
import com.vkplatform.iam.session.api.OAuthClientRegistrationOperations;
import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IamOperatorRunnerTest {
    private static final Instant EXPIRES_AT = Instant.parse("2026-12-01T00:00:00Z");
    private SigningKeyOperations signingKeys;
    private OAuthClientRegistrationOperations registrations;
    private OAuthClientOperations credentials;
    private IamMaintenanceOperations maintenance;
    private IamSecurityTelemetry telemetry;

    @BeforeEach
    void setUp() {
        signingKeys = mock(SigningKeyOperations.class);
        registrations = mock(OAuthClientRegistrationOperations.class);
        credentials = mock(OAuthClientOperations.class);
        maintenance = mock(IamMaintenanceOperations.class);
        telemetry = mock(IamSecurityTelemetry.class);
    }

    @Test
    void dispatchesEverySupportedOperationWithOneValidatedContext() {
        run("bootstrap-signing-key", "bootstrap-1");
        run("rotate-signing-key", "rotate-1");
        run("retire-signing-keys", "retire-1");
        run("reencrypt-signing-keys", "reencrypt-1");
        run("compromise-signing-key", "compromise-1");
        run("reconcile-oauth-clients", "reconcile-1");
        run("rotate-oauth-client-secret", "client-rotate-1");
        run("cleanup-iam-data", "cleanup-1");

        verify(signingKeys).bootstrap(context("bootstrap-1"));
        verify(signingKeys).rotate(context("rotate-1"));
        verify(signingKeys).retireExpired(context("retire-1"));
        verify(signingKeys).reencrypt(25, context("reencrypt-1"));
        verify(signingKeys).compromise("rsa-target", context("compromise-1"));
        verify(registrations).reconcile(context("reconcile-1"));
        verify(credentials).rotateSecret(new RotateOAuthClientSecret(
                "vk-platform-service",
                "n".repeat(32),
                EXPIRES_AT,
                context("client-rotate-1")
        ));
        verify(maintenance).cleanup(context("cleanup-1"));
    }

    @Test
    void ordinaryStartupDoesNothingAndUnknownOperationsFailBeforeMutation() {
        run("", "unused");
        verifyNoInteractions(signingKeys, registrations, credentials, maintenance, telemetry);

        assertThatThrownBy(() -> run("unknown", "unknown-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported");
        verifyNoInteractions(signingKeys, registrations, credentials, maintenance, telemetry);
    }

    @Test
    void validatesRequiredArgumentsBeforeMutation() {
        IamOperationsProperties invalid = properties(
                "compromise-signing-key",
                "compromise-missing-key",
                "",
                "vk-platform-service",
                "n".repeat(32)
        );

        assertThatThrownBy(() -> runner(invalid).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OPERATOR_KEY_ID");
        verify(signingKeys, never()).compromise(any(), any());
    }

    @Test
    void recordsAFrameworkBoundedFailureOutcomeAndRethrowsTheOperationFailure() {
        when(signingKeys.rotate(any())).thenThrow(new IllegalStateException("rotation failed"));

        assertThatThrownBy(() -> run("rotate-signing-key", "rotate-failure"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rotation failed");
        verify(telemetry).recordOperator(
                IamSecurityTelemetry.Operation.OPERATOR_ROTATE_SIGNING_KEY,
                IamSecurityTelemetry.Outcome.FAILURE,
                0L
        );
    }

    private void run(String operation, String operationId) {
        runner(properties(
                operation,
                operationId,
                "rsa-target",
                "vk-platform-service",
                "n".repeat(32)
        )).run(new DefaultApplicationArguments());
    }

    private IamOperatorRunner runner(IamOperationsProperties properties) {
        return new IamOperatorRunner(
                properties,
                signingKeys,
                registrations,
                credentials,
                maintenance,
                telemetry
        );
    }

    private static IamOperationsProperties properties(
            String operation,
            String operationId,
            String keyId,
            String clientId,
            String clientSecret
    ) {
        return new IamOperationsProperties(
                false,
                Duration.ofDays(90),
                Duration.ofDays(30),
                Duration.ofDays(365),
                Duration.ofDays(90),
                100,
                Duration.ofMinutes(15),
                Duration.ofMinutes(15),
                operation,
                "security-operator",
                operationId,
                keyId,
                clientId,
                clientSecret,
                EXPIRES_AT,
                25
        );
    }

    private static OperatorContext context(String operationId) {
        return new OperatorContext("security-operator", operationId);
    }
}
