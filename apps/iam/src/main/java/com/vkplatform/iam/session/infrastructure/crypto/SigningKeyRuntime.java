package com.vkplatform.iam.session.infrastructure.crypto;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;

import java.util.List;

public interface SigningKeyRuntime {
    String activeSigningKeyId();

    List<JWK> select(JWKSelector selector);

    Readiness readiness();

    record Readiness(boolean usable, String reason) {
    }
}
