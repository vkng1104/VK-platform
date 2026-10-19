package com.vkplatform.iam.session.infrastructure.config;

import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.vkplatform.iam.session.infrastructure.persistence.repository.JpaSigningKeyStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionConfigurationTest {
    @AfterEach
    void clearSignerContext() {
        ActiveSignerContext.take();
    }

    @Test
    void signingSourceUsesTheAtomicSignerSnapshotCapturedDuringClaimCustomization() throws Exception {
        RSAKey capturedBeforeRotation = signer("key-before-rotation");
        RSAKey activeAfterRotation = signer("key-after-rotation");
        JpaSigningKeyStore signingKeys = mock(JpaSigningKeyStore.class);
        when(signingKeys.activeSigner()).thenReturn(activeAfterRotation);
        ActiveSignerContext.bind(capturedBeforeRotation);
        var source = new SessionConfiguration().signingJwkSource(
                signingKeys,
                mock(SessionSecurityMetrics.class)
        );

        var selected = source.get(
                new JWKSelector(new JWKMatcher.Builder().keyID("key-before-rotation").build()),
                null
        );

        assertThat(selected).containsExactly(capturedBeforeRotation);
        assertThat(selected.getFirst().isPrivate()).isTrue();
    }

    private static RSAKey signer(String keyId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(keyId)
                .build();
    }
}
