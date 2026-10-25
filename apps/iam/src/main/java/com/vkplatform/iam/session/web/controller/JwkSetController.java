package com.vkplatform.iam.session.web.controller;

import com.nimbusds.jose.jwk.RSAKey;
import com.vkplatform.iam.session.infrastructure.config.OAuthProperties;
import com.vkplatform.iam.session.web.dto.JsonWebKeyResponse;
import com.vkplatform.iam.session.web.dto.JsonWebKeySetResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "iam.oauth.enabled", havingValue = "true")
public class JwkSetController {
    private final RSAKey signingKey;
    private final OAuthProperties properties;

    public JwkSetController(RSAKey signingKey, OAuthProperties properties) {
        this.signingKey = signingKey;
        this.properties = properties;
    }

    @GetMapping("/oauth2/jwks")
    ResponseEntity<JsonWebKeySetResponse> keys() {
        RSAKey publicKey = signingKey.toPublicJWK();
        JsonWebKeyResponse response = new JsonWebKeyResponse(
                publicKey.getKeyType().getValue(),
                publicKey.getKeyUse().identifier(),
                publicKey.getKeyID(),
                publicKey.getAlgorithm().getName(),
                publicKey.getModulus().toString(),
                publicKey.getPublicExponent().toString()
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(properties.jwksCacheTtl()).cachePublic().mustRevalidate())
                .body(new JsonWebKeySetResponse(java.util.List.of(response)));
    }
}
