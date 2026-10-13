package com.vkplatform.iam.session.infrastructure.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.session.api.SessionOperations;
import com.vkplatform.iam.session.application.port.out.SessionRepository;
import com.vkplatform.iam.session.application.service.SessionService;
import com.vkplatform.iam.session.domain.SessionPolicy;
import com.vkplatform.iam.session.infrastructure.crypto.AesGcmSigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.persistence.repository.JpaSigningKeyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                properties.signingKeyEncryptionKeyBase64(),
                new SecureRandom()
        );
    }

    @Bean
    JWKSource<SecurityContext> tokenJwkSource(JpaSigningKeyStore signingKeys) {
        return (selector, context) -> {
            List<JWK> keys = new ArrayList<>(signingKeys.publishedKeys());
            return selector.select(new JWKSet(keys));
        };
    }

    @Bean
    JwtEncoder accessTokenEncoder(JWKSource<SecurityContext> tokenJwkSource) {
        return new NimbusJwtEncoder(tokenJwkSource);
    }

    @Bean
    JwtDecoder accessTokenDecoder(
            JWKSource<SecurityContext> tokenJwkSource,
            SessionProperties properties
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(tokenJwkSource)
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
}
