package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SigningKeyOperatorRunnerTest {
    @Test
    void dispatchesTheExplicitOperationWithItsValidatedContextAndArguments() throws Exception {
        SigningKeyProperties properties = mock(SigningKeyProperties.class);
        SigningKeyOperations operations = mock(SigningKeyOperations.class);
        when(properties.operatorOperation()).thenReturn("compromise");
        when(properties.operatorActor()).thenReturn("security-operator");
        when(properties.operatorOperationId()).thenReturn("compromise-2026-10-29");
        when(properties.operatorKeyId()).thenReturn("rsa-target");

        new SigningKeyOperatorRunner(properties, operations)
                .run(new DefaultApplicationArguments());

        ArgumentCaptor<OperatorContext> context = ArgumentCaptor.forClass(OperatorContext.class);
        verify(operations).compromise(org.mockito.ArgumentMatchers.eq("rsa-target"), context.capture());
        assertThat(context.getValue()).isEqualTo(new OperatorContext(
                "security-operator",
                "compromise-2026-10-29"
        ));
    }

    @Test
    void dispatchesBoundedReencryptionAndDoesNothingOnOrdinaryStartup() throws Exception {
        SigningKeyProperties properties = mock(SigningKeyProperties.class);
        SigningKeyOperations operations = mock(SigningKeyOperations.class);
        SigningKeyOperatorRunner runner = new SigningKeyOperatorRunner(properties, operations);
        when(properties.operatorOperation()).thenReturn("");

        runner.run(new DefaultApplicationArguments());

        verify(operations, never()).bootstrap(any());
        when(properties.operatorOperation()).thenReturn("reencrypt");
        when(properties.operatorActor()).thenReturn("security-operator");
        when(properties.operatorOperationId()).thenReturn("reencrypt-1");
        when(properties.operatorBatchSize()).thenReturn(25);

        runner.run(new DefaultApplicationArguments());

        verify(operations).reencrypt(
                org.mockito.ArgumentMatchers.eq(25),
                org.mockito.ArgumentMatchers.eq(new OperatorContext("security-operator", "reencrypt-1"))
        );
    }

    @Test
    void rejectsUnknownOperationsBeforeMutation() {
        SigningKeyProperties properties = mock(SigningKeyProperties.class);
        SigningKeyOperations operations = mock(SigningKeyOperations.class);
        when(properties.operatorOperation()).thenReturn("unknown");
        when(properties.operatorActor()).thenReturn("security-operator");
        when(properties.operatorOperationId()).thenReturn("unknown-1");

        assertThatThrownBy(() -> new SigningKeyOperatorRunner(properties, operations)
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported");
        verify(operations, never()).rotate(any());
    }
}
