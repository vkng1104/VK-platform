package com.vkplatform.iam.session.infrastructure.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.session.api.SessionOperations;
import com.vkplatform.iam.session.application.port.out.SessionRepository;
import com.vkplatform.iam.session.application.service.SessionService;
import com.vkplatform.iam.session.application.service.SessionRetentionService;
import com.vkplatform.iam.session.application.port.out.SessionRetentionRepository;
import com.vkplatform.iam.session.domain.SessionPolicy;
import com.vkplatform.iam.session.infrastructure.crypto.AesGcmSigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.persistence.repository.JpaSigningKeyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "iam.session.enabled",
        havingValue = "true"
)
public class SessionConfiguration {
    @Bean
    SessionPolicy sessionPolicy(SessionProperties properties) {
        return new SessionPolicy(
                properties.issuer(),
                properties.authenticationAudience(),
                properties.bffClientId(),
                properties.accessTokenTtl(),
                properties.refreshTokenTtl(),
                properties.absoluteSessionTtl()
        );
    }

    @Bean
    SigningKeyProtector signingKeyProtector(SessionProperties properties) {
        return new AesGcmSigningKeyProtector(
                properties.signingKeyProtectionVersion(),
                protectionKeys(properties),
                new SecureRandom()
        );
    }

    private static Map<String, String> protectionKeys(SessionProperties properties) {
        String configured = properties.signingKeyProtectionKeys();
        if (configured == null || configured.isBlank()) {
            return Map.of(
                    properties.signingKeyProtectionVersion(),
                    properties.signingKeyEncryptionKeyBase64()
            );
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : configured.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalStateException("IAM_SIGNING_KEY_PROTECTION_KEYS is invalid");
            }
            String version = entry.substring(0, separator).strip();
            String key = entry.substring(separator + 1).strip();
            if (result.putIfAbsent(version, key) != null) {
                throw new IllegalStateException("duplicate signing-key protection version");
            }
        }
        return Map.copyOf(result);
    }

    @Bean
    @Primary
    JWKSource<SecurityContext> publicJwkSource(
            JpaSigningKeyStore signingKeys,
            SessionSecurityMetrics metrics
    ) {
        return (selector, context) -> {
            metrics.jwkLookup();
            List<JWK> keys = new ArrayList<>(signingKeys.publishedPublicKeys());
            List<JWK> selected = selector.select(new JWKSet(keys));
            if (selected.isEmpty()) {
                metrics.unknownJwk();
            }
            return selected;
        };
    }

    @Bean
    JWKSource<SecurityContext> signingJwkSource(
            JpaSigningKeyStore signingKeys,
            SessionSecurityMetrics metrics
    ) {
        return (selector, context) -> {
            metrics.jwkLookup();
            JWK signer = ActiveSignerContext.take();
            if (signer == null) {
                signer = signingKeys.activeSigner();
            }
            return selector.select(new JWKSet(signer));
        };
    }

    @Bean
    JwtEncoder accessTokenEncoder(
            @Qualifier("signingJwkSource") JWKSource<SecurityContext> signingJwkSource
    ) {
        return new NimbusJwtEncoder(signingJwkSource);
    }

    @Bean
    JwtDecoder accessTokenDecoder(
            @Qualifier("publicJwkSource") JWKSource<SecurityContext> publicJwkSource,
            SessionProperties properties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(publicJwkSource)
                .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();
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

    @Bean
    SessionOperations sessionOperations(
            PasswordlessIdentityOperations identities,
            SessionRepository repository,
            SessionPolicy policy,
            Clock clock
    ) {
        return new SessionService(
                identities,
                repository,
                policy,
                clock
        );
    }

    @Bean
    SessionRetentionService sessionRetentionService(
            SessionRetentionRepository repository,
            SessionProperties properties,
            Clock clock
    ) {
        return new SessionRetentionService(repository, properties, clock);
    }
}
