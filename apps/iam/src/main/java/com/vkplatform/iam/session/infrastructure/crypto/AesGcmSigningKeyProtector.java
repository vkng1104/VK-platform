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
    private final Map<String, SecretKeySpec> protectionKeys;
    private final SecureRandom random;

    public AesGcmSigningKeyProtector(
            String currentVersion,
            Map<String, String> encodedProtectionKeys,
            SecureRandom random
    ) {
        if (currentVersion == null || currentVersion.isBlank() || currentVersion.length() > 64) {
            throw new IllegalStateException("current signing-key protection version is invalid");
        }
        if (encodedProtectionKeys == null || encodedProtectionKeys.isEmpty()) {
            throw new IllegalStateException("signing-key protection keys are required");
        }
        Map<String, SecretKeySpec> decoded = new LinkedHashMap<>();
        encodedProtectionKeys.forEach((version, encoded) -> decoded.put(
                requireVersion(version),
                decode(encoded)
        ));
        if (!decoded.containsKey(currentVersion)) {
            throw new IllegalStateException("current signing-key protection version has no configured key");
        }
        this.currentVersion = currentVersion;
        this.protectionKeys = Map.copyOf(decoded);
        this.random = java.util.Objects.requireNonNull(random);
    }

    public static Map<String, String> parseProtectionKeys(String configured) {
        if (configured == null || configured.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : configured.split(",")) {
            String[] pair = entry.strip().split("=", 2);
            if (pair.length != 2 || pair[1].isBlank()) {
                throw new IllegalStateException("IAM_SIGNING_KEY_PROTECTION_KEYS is invalid");
            }
            String previous = result.put(requireVersion(pair[0].strip()), pair[1].strip());
            if (previous != null) {
                throw new IllegalStateException("signing-key protection version is duplicated");
            }
        }
        return Map.copyOf(result);
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
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalStateException("signing-key encryption nonce is invalid");
        }
        return cipher(Cipher.DECRYPT_MODE, keyId, version, ciphertext, nonce);
    }

    private byte[] cipher(int mode, String keyId, String version, byte[] value, byte[] nonce) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalStateException("signing-key ID is invalid");
        }
        try {
            SecretKeySpec protectionKey = protectionKeys.get(version);
            if (protectionKey == null) {
                throw new IllegalStateException("signing-key protection version is unavailable");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, protectionKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD((keyId + "\0" + version).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("signing-key protection operation failed", exception);
        }
    }

    private static SecretKeySpec decode(String encoded) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("signing-key protection key is not valid Base64", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("signing-key protection key must contain exactly 32 bytes");
        }
        return new SecretKeySpec(decoded, "AES");
    }

    private static String requireVersion(String version) {
        if (version == null
                || !version.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalStateException("signing-key protection version is invalid");
        }
        return version;
    }
}
