package com.vkplatform.iam.session.infrastructure.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmSigningKeyProtectorTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithAUniqueNonceAndBindsCiphertextToTheKeyId() {
        AesGcmSigningKeyProtector protector = new AesGcmSigningKeyProtector(KEY, new SecureRandom());
        byte[] privateKey = "private-key-material".getBytes(StandardCharsets.UTF_8);

        SigningKeyProtector.EncryptedPrivateKey first = protector.encrypt("key-1", privateKey);
        SigningKeyProtector.EncryptedPrivateKey second = protector.encrypt("key-1", privateKey);

        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(privateKey);
        assertThat(protector.decrypt("key-1", first.ciphertext(), first.nonce()))
                .containsExactly(privateKey);
        assertThatThrownBy(() -> protector.decrypt("key-2", first.ciphertext(), first.nonce()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresA256BitEncryptionKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new AesGcmSigningKeyProtector(shortKey, new SecureRandom()))
                .isInstanceOf(IllegalStateException.class);
    }
}
