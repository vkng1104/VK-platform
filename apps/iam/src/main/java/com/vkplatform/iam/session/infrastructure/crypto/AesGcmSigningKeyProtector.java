package com.vkplatform.iam.session.infrastructure.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AesGcmSigningKeyProtector implements SigningKeyProtector {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final String currentVersion;
    private final Map<String, SecretKeySpec> encryptionKeys;
    private final SecureRandom random;

    public AesGcmSigningKeyProtector(String encryptionKeyBase64, SecureRandom random) {
        this("v1", Map.of("v1", encryptionKeyBase64), random);
    }

    public AesGcmSigningKeyProtector(
            String currentVersion,
            Map<String, String> encryptionKeysBase64,
            SecureRandom random
    ) {
        if (currentVersion == null || currentVersion.isBlank()) {
            throw new IllegalStateException("current signing-key protection version is required");
        }
        Map<String, SecretKeySpec> decoded = new LinkedHashMap<>();
        encryptionKeysBase64.forEach((version, encoded) -> decoded.put(version, decode(version, encoded)));
        if (!decoded.containsKey(currentVersion)) {
            throw new IllegalStateException("current signing-key protection version has no configured key");
        }
        this.currentVersion = currentVersion;
        this.encryptionKeys = Map.copyOf(decoded);
        this.random = random;
    }

    @Override
    public String currentVersion() {
        return currentVersion;
    }

    @Override
    public EncryptedPrivateKey encrypt(String keyId, byte[] privateKey) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return new EncryptedPrivateKey(
                currentVersion,
                cipher(Cipher.ENCRYPT_MODE, keyId, currentVersion, privateKey, nonce),
                nonce
        );
    }

    @Override
    public byte[] decrypt(String keyId, String version, byte[] ciphertext, byte[] nonce) {
        if (nonce.length != NONCE_BYTES) {
            throw new IllegalStateException("signing-key encryption nonce is invalid");
        }
        return cipher(Cipher.DECRYPT_MODE, keyId, version, ciphertext, nonce);
    }

    private byte[] cipher(int mode, String keyId, String version, byte[] value, byte[] nonce) {
        try {
            SecretKeySpec encryptionKey = encryptionKeys.get(version);
            if (encryptionKey == null) {
                throw new IllegalStateException("signing-key protection version is unavailable");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, encryptionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD((keyId + "\0" + version).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("signing-key encryption operation failed", exception);
        }
    }

    private static SecretKeySpec decode(String version, String encoded) {
        if (version == null || version.isBlank() || version.length() > 64) {
            throw new IllegalStateException("signing-key protection version is invalid");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("signing-key encryption key is not valid Base64", exception);
        }
        if (key.length != 32) {
            throw new IllegalStateException("signing-key encryption key must contain 32 bytes");
        }
        return new SecretKeySpec(key, "AES");
    }
}
