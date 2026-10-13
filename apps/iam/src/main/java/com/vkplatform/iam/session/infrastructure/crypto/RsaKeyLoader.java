package com.vkplatform.iam.session.infrastructure.crypto;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class RsaKeyLoader {
    private RsaKeyLoader() {
    }

    public static KeyPair load(String privateKeyPkcs8Base64, String publicKeyX509Base64) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getDecoder().decode(privateKeyPkcs8Base64)
            ));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(publicKeyX509Base64)
            ));
            if (!(privateKey instanceof RSAPrivateKey) || !(publicKey instanceof RSAPublicKey)) {
                throw new IllegalArgumentException("configured signing key is not RSA");
            }
            return new KeyPair(publicKey, privateKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("configured RSA signing key is invalid", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("configured RSA signing key cannot be loaded", exception);
        }
    }
}
