package com.vkplatform.iam.session.infrastructure.crypto;

public interface SigningKeyProtector {
    EncryptedPrivateKey encrypt(String keyId, byte[] privateKey);

    byte[] decrypt(String keyId, byte[] ciphertext, byte[] nonce);

    record EncryptedPrivateKey(byte[] ciphertext, byte[] nonce) {
        public EncryptedPrivateKey {
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
