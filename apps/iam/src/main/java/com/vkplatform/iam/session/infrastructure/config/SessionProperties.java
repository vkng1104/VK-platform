package com.vkplatform.iam.session.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("iam.session")
public record SessionProperties(
        boolean enabled,
        URI issuer,
        String audience,
        String authenticationAudience,
        String bffClientId,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration absoluteSessionTtl,
        URI loginUri,
        String signingKeyId,
        String signingPrivateKeyPkcs8Base64,
        String signingPublicKeyX509Base64,
        String signingKeyEncryptionKeyBase64,
        String bffClientSecret,
        URI bffRedirectUri,
        String serviceClientId,
        String serviceClientSecret,
        String serviceAudience
) {
}
