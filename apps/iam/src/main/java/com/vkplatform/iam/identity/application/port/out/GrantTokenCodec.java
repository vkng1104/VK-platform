package com.vkplatform.iam.identity.application.port.out;

public interface GrantTokenCodec {
    GeneratedGrant generate();

    byte[] digest(String grant);

    record GeneratedGrant(String value, byte[] digest) {
        public GeneratedGrant {
            digest = digest.clone();
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }
}
