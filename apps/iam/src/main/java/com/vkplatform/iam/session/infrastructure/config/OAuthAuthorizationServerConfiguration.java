package com.vkplatform.iam.session.infrastructure.config;

import com.nimbusds.jose.jwk.RSAKey;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"iam.oauth.enabled", "iam.session.enabled"},
        havingValue = "true"
)
public class OAuthAuthorizationServerConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,
            AuthorizationServerSettings settings,
            SecurityContextRepository securityContexts,
            OAuthAuthorizationProperties authorizationProperties,
            OAuthProperties oauthProperties,
            PlatformTransactionManager transactionManager
    ) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                new OAuth2AuthorizationServerConfigurer();
        http
                .securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server
                        .registeredClientRepository(clients)
                        .authorizationService(authorizations)
                        .authorizationServerSettings(settings)
                )
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/.well-known/oauth-authorization-server").permitAll()
                        .anyRequest().authenticated()
                )
                .headers(headers -> headers
                        .cacheControl(cacheControl -> cacheControl.disable())
                        .addHeaderWriter(new OAuthCacheControlHeadersWriter(
                                settings.getJwkSetEndpoint(),
                                oauthProperties.jwksCacheTtl()
                        ))
                )
                .securityContext(context -> context.securityContextRepository(securityContexts))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new LoginUrlAuthenticationEntryPoint(authorizationProperties.loginUri().toString())
                ))
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServer.getEndpointsMatcher()))
                .addFilterBefore(
                        new OAuthTokenEndpointTransactionFilter(transactionManager),
                        SecurityContextHolderFilter.class
                );
        return http.build();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(OAuthAuthorizationProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.issuer().toString())
                .build();
    }

    @Bean
    PasswordEncoder oauthClientPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    RegisteredClientRepository oauthRegisteredClients(
            SessionProperties sessions,
            OAuthAuthorizationProperties properties,
            PasswordEncoder passwords
    ) {
        RegisteredClient bff = RegisteredClient.withId("bff:" + sessions.bffClientId())
                .clientId(sessions.bffClientId())
                .clientSecret(passwords.encode(sessions.bffClientSecret()))
                .clientName("VK Platform BFF")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.bffRedirectUri().toString())
                .scope("api.access")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(properties.authorizationCodeTtl())
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .build())
                .build();
        return new InMemoryRegisteredClientRepository(bff);
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> oauthTokenClaimsCustomizer(
            AuthenticationSessionRepository sessions,
            OAuthAuthorizationProperties properties,
            RSAKey signingKey,
            Clock clock
    ) {
        return new OAuthTokenClaimsCustomizer(sessions, properties, signingKey, clock);
    }

}
