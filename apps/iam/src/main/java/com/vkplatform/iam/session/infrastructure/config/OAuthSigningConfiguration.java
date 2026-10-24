package com.vkplatform.iam.session.infrastructure.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.vkplatform.iam.session.infrastructure.crypto.RsaKeyLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.oauth.enabled", havingValue = "true")
public class OAuthSigningConfiguration {
    @Bean
    RSAKey oauthSigningKey(OAuthProperties properties) {
        KeyPair pair = RsaKeyLoader.load(
                properties.signingPrivateKeyPkcs8Base64(),
                properties.signingPublicKeyX509Base64()
        );
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(properties.signingKeyId())
                .build();
    }

    @Bean
    JWKSource<SecurityContext> oauthSigningJwkSource(RSAKey signingKey) {
        JWKSet keySet = new JWKSet(signingKey);
        return (selector, context) -> selector.select(keySet);
    }

    @Bean
    JwtEncoder oauthJwtEncoder(JWKSource<SecurityContext> signingKeys) {
        return new NimbusJwtEncoder(signingKeys);
    }
}
