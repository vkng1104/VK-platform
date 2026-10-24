package com.vkplatform.iam.session.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("iam.oauth.authorization")
public record OAuthAuthorizationProperties(
        URI issuer,
        String audience,
        URI loginUri,
        URI bffRedirectUri,
        Duration authorizationCodeTtl,
        Duration accessTokenTtl
) {
    public OAuthAuthorizationProperties {
        requireOrigin(issuer, "IAM_OAUTH_ISSUER");
        requireValue(audience, "IAM_OAUTH_AUDIENCE");
        requireAbsolute(loginUri, "IAM_OAUTH_LOGIN_URI");
        requireAbsolute(bffRedirectUri, "IAM_OAUTH_BFF_REDIRECT_URI");
        requirePositive(authorizationCodeTtl, "IAM_OAUTH_AUTHORIZATION_CODE_TTL");
        requirePositive(accessTokenTtl, "IAM_OAUTH_ACCESS_TOKEN_TTL");
    }

    private static void requireOrigin(URI value, String name) {
        requireAbsolute(value, name);
        if (value.getQuery() != null || !(value.getPath().isEmpty() || "/".equals(value.getPath()))) {
            throw new IllegalStateException(name + " must be an absolute origin");
        }
    }

    private static void requireAbsolute(URI value, String name) {
        if (value == null || !value.isAbsolute() || value.getHost() == null || value.getFragment() != null) {
            throw new IllegalStateException(name + " must be an absolute URI without a fragment");
        }
    }

    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128 || !value.equals(value.strip())) {
            throw new IllegalStateException(name + " is invalid");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be greater than zero");
        }
    }
}
