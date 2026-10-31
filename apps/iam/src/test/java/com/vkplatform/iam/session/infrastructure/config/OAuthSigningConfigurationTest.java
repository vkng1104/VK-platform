package com.vkplatform.iam.session.infrastructure.config;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthSigningConfigurationTest {
    @Test
    void signsAnRs256TokenThatThePublishedPublicKeyCanVerify() throws Exception {
        KeyPair pair = rsaPair();
        OAuthProperties properties = new OAuthProperties(
                true,
                "oauth-signing-2026",
                encoded(pair.getPrivate().getEncoded()),
                encoded(pair.getPublic().getEncoded()),
                Duration.ofMinutes(5)
        );
        OAuthSigningConfiguration configuration = new OAuthSigningConfiguration();
        RSAKey signingKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID("oauth-signing-2026")
                .build();
        OAuthAuthorizationProperties authorization = authorization(Duration.ofMinutes(5));
        SigningKeyRuntime signingKeys = mock(SigningKeyRuntime.class);
        when(signingKeys.select(any())).thenAnswer(invocation -> invocation
                .<com.nimbusds.jose.jwk.JWKSelector>getArgument(0)
                .select(new JWKSet(signingKey)));
        JWKSource<SecurityContext> source = configuration.oauthSigningJwkSource(
                signingKeys,
                properties,
                authorization
        );
        JwtEncoder encoder = configuration.oauthJwtEncoder(source);
        Instant now = Instant.now();

        Jwt encoded = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build(),
                JwtClaimsSet.builder()
                        .issuer("https://iam.example.test")
                        .subject("account-123")
                        .audience(java.util.List.of("vk-platform-api"))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .build()
        ));
        Jwt decoded = NimbusJwtDecoder.withPublicKey((RSAPublicKey) pair.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
                .decode(encoded.getTokenValue());

        assertThat(encoded.getHeaders().get("kid")).isEqualTo("oauth-signing-2026");
        assertThat(decoded.getSubject()).isEqualTo("account-123");

        JwtDecoder applicationDecoder = configuration.oauthJwtDecoder(source, authorization);
        assertThat(applicationDecoder.decode(encoded.getTokenValue()).getSubject())
                .isEqualTo("account-123");
    }

    @Test
    void rejectsAJwksCacheWindowLongerThanTheAccessTokenLifetime() {
        OAuthSigningConfiguration configuration = new OAuthSigningConfiguration();
        OAuthProperties oauth = new OAuthProperties(
                true,
                null,
                null,
                null,
                Duration.ofMinutes(6)
        );

        assertThatThrownBy(() -> configuration.oauthSigningJwkSource(
                mock(SigningKeyRuntime.class),
                oauth,
                authorization(Duration.ofMinutes(5))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_JWKS_CACHE_TTL");
    }

    private static OAuthAuthorizationProperties authorization(Duration accessTokenTtl) {
        return new OAuthAuthorizationProperties(
                URI.create("https://iam.example.test"),
                "vk-platform-api",
                URI.create("https://web.example.test/sign-in"),
                URI.create("https://web.example.test/api/auth/callback"),
                Duration.ofMinutes(5),
                accessTokenTtl,
                Duration.ofDays(30)
        );
    }

    private static KeyPair rsaPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
