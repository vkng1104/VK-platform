package com.vkplatform.iam.session.infrastructure.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

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
        assertThat(protector.decrypt("key-1", first.version(), first.ciphertext(), first.nonce()))
                .containsExactly(privateKey);
        assertThatThrownBy(() -> protector.decrypt("key-2", first.version(), first.ciphertext(), first.nonce()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresA256BitEncryptionKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new AesGcmSigningKeyProtector(shortKey, new SecureRandom()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsOldProtectionVersionsWhileWritingOnlyTheCurrentVersion() {
        byte[] oldKey = new byte[32];
        byte[] currentKey = new byte[32];
        java.util.Arrays.fill(oldKey, (byte) 1);
        java.util.Arrays.fill(currentKey, (byte) 2);
        AesGcmSigningKeyProtector oldProtector = new AesGcmSigningKeyProtector(
                "v1",
                Map.of("v1", Base64.getEncoder().encodeToString(oldKey)),
                new SecureRandom()
        );
        var oldCiphertext = oldProtector.encrypt("key-1", new byte[]{1, 2, 3});
        AesGcmSigningKeyProtector migrating = new AesGcmSigningKeyProtector(
                "v2",
                Map.of(
                        "v1", Base64.getEncoder().encodeToString(oldKey),
                        "v2", Base64.getEncoder().encodeToString(currentKey)
                ),
                new SecureRandom()
        );

        assertThat(migrating.decrypt(
                "key-1",
                oldCiphertext.version(),
                oldCiphertext.ciphertext(),
                oldCiphertext.nonce()
        )).containsExactly(1, 2, 3);
        assertThat(migrating.encrypt("key-1", new byte[]{4, 5, 6}).version()).isEqualTo("v2");
        assertThatThrownBy(() -> new AesGcmSigningKeyProtector(
                "v2",
                Map.of("v2", Base64.getEncoder().encodeToString(new byte[32])),
                new SecureRandom()
        ).decrypt(
                "key-1",
                oldCiphertext.version(),
                oldCiphertext.ciphertext(),
                oldCiphertext.nonce()
        )).isInstanceOf(IllegalStateException.class);
    }
}
