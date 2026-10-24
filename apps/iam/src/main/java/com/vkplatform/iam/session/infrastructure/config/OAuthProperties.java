package com.vkplatform.iam.session.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.regex.Pattern;

@ConfigurationProperties("iam.oauth")
public record OAuthProperties(
        boolean enabled,
        String signingKeyId,
        String signingPrivateKeyPkcs8Base64,
        String signingPublicKeyX509Base64,
        Duration jwksCacheTtl
) {
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public OAuthProperties {
        if (enabled) {
            if (signingKeyId == null || !KEY_ID.matcher(signingKeyId).matches()) {
                throw new IllegalStateException("IAM_OAUTH_SIGNING_KEY_ID is invalid");
            }
            requireValue(signingPrivateKeyPkcs8Base64, "IAM_OAUTH_SIGNING_PRIVATE_KEY_PKCS8_BASE64");
            requireValue(signingPublicKeyX509Base64, "IAM_OAUTH_SIGNING_PUBLIC_KEY_X509_BASE64");
            if (jwksCacheTtl == null || jwksCacheTtl.isZero() || jwksCacheTtl.isNegative()) {
                throw new IllegalStateException("IAM_OAUTH_JWKS_CACHE_TTL must be greater than zero");
            }
        }
    }

    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
    }
}
