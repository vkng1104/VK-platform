package com.vkplatform.iam.session.infrastructure.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class RsaKeyLoader {
    private static final byte[] PAIR_CHALLENGE =
            "vk-platform-iam-signing-key-pair-check".getBytes(StandardCharsets.UTF_8);

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
            if (!(privateKey instanceof RSAPrivateKey) || !(publicKey instanceof RSAPublicKey rsaPublicKey)) {
                throw new IllegalArgumentException("configured signing key is not RSA");
            }
            if (rsaPublicKey.getModulus().bitLength() < 2048 || rsaPublicKey.getModulus().bitLength() > 4096) {
                throw new IllegalArgumentException("configured RSA signing key size is unsupported");
            }
            verifyPair(privateKey, publicKey);
            return new KeyPair(publicKey, privateKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("configured RSA signing key is invalid", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("configured RSA signing key cannot be loaded", exception);
        }
    }

    private static void verifyPair(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(PAIR_CHALLENGE);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(PAIR_CHALLENGE);
        if (!verifier.verify(signature)) {
            throw new IllegalArgumentException("configured RSA signing keys do not form a pair");
        }
    }
}
