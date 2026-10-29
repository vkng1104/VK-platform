package com.vkplatform.iam.session.infrastructure.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmSigningKeyProtectorTest {
    private static final String V1_KEY = encoded(filled((byte) 1));
    private static final String V2_KEY = encoded(filled((byte) 2));

    @Test
    void encryptsWithUniqueNoncesAndBindsCiphertextToKeyIdAndVersion() {
        AesGcmSigningKeyProtector protector = protector("v1", Map.of("v1", V1_KEY));
        byte[] plaintext = "private-key-material".getBytes(StandardCharsets.UTF_8);

        SigningKeyProtector.EncryptedPrivateKey first = protector.encrypt("key-1", plaintext);
        SigningKeyProtector.EncryptedPrivateKey second = protector.encrypt("key-1", plaintext);

        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(plaintext);
        assertThat(protector.decrypt(
                "key-1",
                first.version(),
                first.ciphertext(),
                first.nonce()
        )).containsExactly(plaintext);
        assertThatThrownBy(() -> protector.decrypt(
                "key-2",
                first.version(),
                first.ciphertext(),
                first.nonce()
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> protector("v2", Map.of("v2", V2_KEY)).decrypt(
                "key-1",
                "v2",
                first.ciphertext(),
                first.nonce()
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsOldVersionsAndWritesOnlyTheConfiguredCurrentVersion() {
        AesGcmSigningKeyProtector old = protector("v1", Map.of("v1", V1_KEY));
        SigningKeyProtector.EncryptedPrivateKey ciphertext = old.encrypt(
                "key-1",
                new byte[]{1, 2, 3}
        );
        AesGcmSigningKeyProtector rotating = protector(
                "v2",
                Map.of("v1", V1_KEY, "v2", V2_KEY)
        );

        assertThat(rotating.decrypt(
                "key-1",
                ciphertext.version(),
                ciphertext.ciphertext(),
                ciphertext.nonce()
        )).containsExactly(1, 2, 3);
        assertThat(rotating.encrypt("key-1", new byte[]{4}).version()).isEqualTo("v2");
    }

    @Test
    void parsesTheEnvironmentKeyRingAndRejectsMalformedOrWeakConfiguration() {
        assertThat(AesGcmSigningKeyProtector.parseProtectionKeys(
                "v1=" + V1_KEY + ",v2=" + V2_KEY
        )).containsExactlyInAnyOrderEntriesOf(Map.of("v1", V1_KEY, "v2", V2_KEY));
        assertThatThrownBy(() -> AesGcmSigningKeyProtector.parseProtectionKeys("v1"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AesGcmSigningKeyProtector.parseProtectionKeys(
                "v1=" + V1_KEY + ",v1=" + V2_KEY
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> protector(
                "v1",
                Map.of("v1", encoded(new byte[16]))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> protector("v2", Map.of("v1", V1_KEY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no configured key");
    }

    @Test
    void encryptedValuesDefensivelyCopyTheirArrays() {
        byte[] ciphertext = new byte[]{1, 2};
        byte[] nonce = new byte[12];
        SigningKeyProtector.EncryptedPrivateKey encrypted =
                new SigningKeyProtector.EncryptedPrivateKey("v1", ciphertext, nonce);
        ciphertext[0] = 9;
        nonce[0] = 9;

        assertThat(encrypted.ciphertext()).containsExactly(1, 2);
        assertThat(encrypted.nonce()[0]).isZero();
        byte[] returned = encrypted.ciphertext();
        returned[0] = 7;
        assertThat(encrypted.ciphertext()).containsExactly(1, 2);
    }

    private static AesGcmSigningKeyProtector protector(
            String currentVersion,
            Map<String, String> keys
    ) {
        return new AesGcmSigningKeyProtector(currentVersion, keys, new SecureRandom());
    }

    private static byte[] filled(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
