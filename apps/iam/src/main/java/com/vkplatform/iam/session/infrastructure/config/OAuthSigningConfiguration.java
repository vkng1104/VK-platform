package com.vkplatform.iam.session.infrastructure.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.vkplatform.iam.session.infrastructure.crypto.AesGcmSigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyRuntime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.security.SecureRandom;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.oauth.enabled", havingValue = "true")
public class OAuthSigningConfiguration {
    @Bean
    SigningKeyProtector signingKeyProtector(
            SigningKeyProperties properties,
            SecureRandom random
    ) {
        return new AesGcmSigningKeyProtector(
                properties.protectionVersion(),
                AesGcmSigningKeyProtector.parseProtectionKeys(properties.protectionKeys()),
                random
        );
    }

    @Bean
    JWKSource<SecurityContext> oauthSigningJwkSource(
            SigningKeyRuntime signingKeys,
            OAuthProperties oauth,
            OAuthAuthorizationProperties authorization
    ) {
        if (oauth.jwksCacheTtl().compareTo(authorization.accessTokenTtl()) > 0) {
            throw new IllegalStateException(
                    "IAM_OAUTH_JWKS_CACHE_TTL cannot exceed IAM_OAUTH_ACCESS_TOKEN_TTL"
            );
        }
        return (selector, context) -> signingKeys.select(selector);
    }

    @Bean
    JwtEncoder oauthJwtEncoder(JWKSource<SecurityContext> signingKeys) {
        return new NimbusJwtEncoder(signingKeys);
    }

    @Bean
    JwtDecoder oauthJwtDecoder(
            JWKSource<SecurityContext> signingKeys,
            OAuthAuthorizationProperties properties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(signingKeys).build();
        OAuth2TokenValidator<Jwt> audience = token -> token.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "The required audience is missing.",
                        null
                ));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer().toString()),
                audience
        ));
        return decoder;
    }
}
