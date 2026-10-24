package com.vkplatform.iam.session.infrastructure.crypto;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RsaKeyLoaderTest {
    @Test
    void acceptsAMatchingSupportedRsaPair() throws Exception {
        KeyPair pair = pair("RSA", 2048);

        KeyPair loaded = RsaKeyLoader.load(encoded(pair.getPrivate().getEncoded()), encoded(pair.getPublic().getEncoded()));

        assertThat(loaded.getPrivate().getEncoded()).containsExactly(pair.getPrivate().getEncoded());
        assertThat(loaded.getPublic().getEncoded()).containsExactly(pair.getPublic().getEncoded());
    }

    @Test
    void rejectsMismatchedRsaPairs() throws Exception {
        KeyPair first = pair("RSA", 2048);
        KeyPair second = pair("RSA", 2048);

        assertThatThrownBy(() -> RsaKeyLoader.load(
                encoded(first.getPrivate().getEncoded()),
                encoded(second.getPublic().getEncoded())
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void rejectsMalformedNonRsaAndUnsupportedKeys() throws Exception {
        KeyPair ec = pair("EC", 256);
        KeyPair weakRsa = pair("RSA", 1024);

        assertThatThrownBy(() -> RsaKeyLoader.load("not-base64", "not-base64"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RsaKeyLoader.load(
                encoded(ec.getPrivate().getEncoded()),
                encoded(ec.getPublic().getEncoded())
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RsaKeyLoader.load(
                encoded(weakRsa.getPrivate().getEncoded()),
                encoded(weakRsa.getPublic().getEncoded())
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid");
    }

    private static KeyPair pair(String algorithm, int size) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        generator.initialize(size);
        return generator.generateKeyPair();
    }

    private static String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
