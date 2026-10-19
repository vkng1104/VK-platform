package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.OAuthClientOperations;
import com.vkplatform.iam.session.api.OAuthClientRegistrationOperations;
import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.application.service.SessionRetentionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionOperatorRunnerTest {
    @Test
    void performsOnlyTheExplicitRequestedOperationWithAuditContext() throws Exception {
        SessionProperties properties = mock(SessionProperties.class);
        SigningKeyOperations signingKeys = mock(SigningKeyOperations.class);
        when(properties.operatorOperation()).thenReturn("rotate-signing-key");
        when(properties.operatorActor()).thenReturn("security-operator");
        when(properties.operatorOperationId()).thenReturn("rotation-2026-10-19");
        SessionOperatorRunner runner = new SessionOperatorRunner(
                properties,
                signingKeys,
                mock(OAuthClientRegistrationOperations.class),
                mock(OAuthClientOperations.class),
                mock(SessionRetentionService.class)
        );

        runner.run(new DefaultApplicationArguments());

        ArgumentCaptor<OperatorContext> context = ArgumentCaptor.forClass(OperatorContext.class);
        verify(signingKeys).rotate(context.capture());
        assertThat(context.getValue()).isEqualTo(new OperatorContext(
                "security-operator",
                "rotation-2026-10-19"
        ));
        verify(signingKeys, never()).bootstrap(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ordinaryStartupDoesNotMutateOperationalState() throws Exception {
        SessionProperties properties = mock(SessionProperties.class);
        SigningKeyOperations signingKeys = mock(SigningKeyOperations.class);
        when(properties.operatorOperation()).thenReturn("");
        SessionOperatorRunner runner = new SessionOperatorRunner(
                properties,
                signingKeys,
                mock(OAuthClientRegistrationOperations.class),
                mock(OAuthClientOperations.class),
                mock(SessionRetentionService.class)
        );

        runner.run(new DefaultApplicationArguments());

        verify(signingKeys, never()).rotate(org.mockito.ArgumentMatchers.any());
        verify(signingKeys, never()).bootstrap(org.mockito.ArgumentMatchers.any());
    }
}
