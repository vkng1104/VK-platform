package com.vkplatform.iam.session.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import com.vkplatform.iam.session.domain.SessionPolicy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

@ConfigurationProperties("iam.session")
@Validated
public record SessionProperties(
        boolean enabled,
        URI issuer,
        String audience,
        String authenticationAudience,
        String bffClientId,
        Duration accessTokenTtl,
        Duration jwksCacheTtl,
        Duration refreshTokenTtl,
        Duration absoluteSessionTtl,
        URI loginUri,
        URI bffOrigin,
        String signingKeyId,
        String signingPrivateKeyPkcs8Base64,
        String signingPublicKeyX509Base64,
        String signingKeyEncryptionKeyBase64,
        String signingKeyProtectionVersion,
        String signingKeyProtectionKeys,
        String bffClientSecret,
        URI bffRedirectUri,
        String serviceClientId,
        String serviceClientSecret,
        String serviceAudience,
        String browserCookieName,
        boolean browserCookieSecure,
        boolean cleanupEnabled,
        Duration sessionRetention,
        Duration authorizationRetention,
        Duration auditRetention,
        int cleanupBatchSize,
        String operatorOperation,
        String operatorActor,
        String operatorOperationId,
        String operatorKeyId,
        String operatorClientId,
        String operatorClientSecret,
        Instant operatorClientSecretExpiresAt,
        int operatorBatchSize
) {
    public SessionProperties {
        if (enabled) {
            new SessionPolicy(
                issuer,
                authenticationAudience,
                bffClientId,
                accessTokenTtl,
                refreshTokenTtl,
                absoluteSessionTtl
            );
            requireAbsolute(loginUri, "IAM_LOGIN_URI");
            requireAbsolute(bffOrigin, "IAM_BFF_ORIGIN");
            requireAbsolute(bffRedirectUri, "IAM_BFF_REDIRECT_URI");
            requireValue(audience, "IAM_TOKEN_AUDIENCE");
            requireValue(serviceClientId, "IAM_SERVICE_CLIENT_ID");
            requireValue(serviceAudience, "IAM_SERVICE_TOKEN_AUDIENCE");
            requireValue(signingKeyProtectionVersion, "IAM_SIGNING_KEY_PROTECTION_VERSION");
            requireValue(browserCookieName, "IAM_SESSION_COOKIE_NAME");
            requirePositive(jwksCacheTtl, "IAM_OAUTH_JWKS_CACHE_TTL");
            requirePositive(sessionRetention, "IAM_SESSION_RETENTION");
            requirePositive(authorizationRetention, "IAM_AUTHORIZATION_RETENTION");
            requirePositive(auditRetention, "IAM_SECURITY_AUDIT_RETENTION");
            if (cleanupBatchSize <= 0 || cleanupBatchSize > 1_000) {
                throw new IllegalStateException("IAM_SESSION_CLEANUP_BATCH_SIZE must be between 1 and 1000");
            }
            if (operatorBatchSize <= 0 || operatorBatchSize > 1_000) {
                throw new IllegalStateException("IAM_SESSION_OPERATOR_BATCH_SIZE must be between 1 and 1000");
            }
        }
    }

    private static void requireAbsolute(URI value, String name) {
        if (value == null || !value.isAbsolute() || value.getFragment() != null) {
            throw new IllegalStateException(name + " must be an absolute URI without a fragment");
        }
    }

    private static void requireValue(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalStateException(name + " is invalid");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(name + " must be greater than zero");
        }
    }
}
