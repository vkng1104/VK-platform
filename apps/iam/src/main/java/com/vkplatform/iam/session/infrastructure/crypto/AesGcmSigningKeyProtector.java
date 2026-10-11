package com.vkplatform.iam.session.infrastructure.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public final class AesGcmSigningKeyProtector implements SigningKeyProtector {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec encryptionKey;
    private final SecureRandom random;

    public AesGcmSigningKeyProtector(String encryptionKeyBase64, SecureRandom random) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encryptionKeyBase64);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("signing-key encryption key is not valid Base64", exception);
        }
        if (key.length != 32) {
            throw new IllegalStateException("signing-key encryption key must contain 32 bytes");
        }
        this.encryptionKey = new SecretKeySpec(key, "AES");
        this.random = random;
    }

    @Override
    public EncryptedPrivateKey encrypt(String keyId, byte[] privateKey) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return new EncryptedPrivateKey(cipher(Cipher.ENCRYPT_MODE, keyId, privateKey, nonce), nonce);
    }

    @Override
    public byte[] decrypt(String keyId, byte[] ciphertext, byte[] nonce) {
        if (nonce.length != NONCE_BYTES) {
            throw new IllegalStateException("signing-key encryption nonce is invalid");
        }
        return cipher(Cipher.DECRYPT_MODE, keyId, ciphertext, nonce);
    }

    private byte[] cipher(int mode, String keyId, byte[] value, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("signing-key encryption operation failed", exception);
        }
    }
}
