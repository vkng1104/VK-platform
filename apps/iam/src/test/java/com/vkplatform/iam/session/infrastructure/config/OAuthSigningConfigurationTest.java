package com.vkplatform.iam.session.infrastructure.config;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

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
        RSAKey signingKey = configuration.oauthSigningKey(properties);
        JWKSource<SecurityContext> source = configuration.oauthSigningJwkSource(signingKey);
        JwtEncoder encoder = configuration.oauthJwtEncoder(source);
        Instant now = Instant.now();

        Jwt encoded = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.getKeyID()).build(),
                JwtClaimsSet.builder()
                        .issuer("https://iam.example.test")
                        .subject("account-123")
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
