package com.vkplatform.iam.session.infrastructure.crypto;

public interface SigningKeyProtector {
    String currentVersion();

    EncryptedPrivateKey encrypt(String keyId, byte[] privateKey);

    byte[] decrypt(String keyId, String version, byte[] ciphertext, byte[] nonce);

    record EncryptedPrivateKey(String version, byte[] ciphertext, byte[] nonce) {
        public EncryptedPrivateKey {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("protection-key version is required");
            }
            ciphertext = ciphertext.clone();
            nonce = nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }
}
