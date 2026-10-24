package com.vkplatform.iam.session.infrastructure.config;

import com.nimbusds.jose.jwk.RSAKey;

final class ActiveSignerContext {
    private static final ThreadLocal<RSAKey> ACTIVE = new ThreadLocal<>();

    private ActiveSignerContext() {
    }

    static void bind(RSAKey signer) {
        ACTIVE.set(signer);
    }

    static RSAKey take() {
        RSAKey signer = ACTIVE.get();
        ACTIVE.remove();
        return signer;
    }
}
