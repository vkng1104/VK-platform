package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.session.infrastructure.persistence.repository.JpaSigningKeyStore;
import com.vkplatform.iam.session.application.port.out.SessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class OAuthAuthorizationServerConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,
            AuthorizationServerSettings settings,
            SecurityContextRepository securityContexts,
            SessionProperties properties,
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
                        .requestMatchers(
                                "/.well-known/oauth-authorization-server",
                                "/oauth2/jwks"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .securityContext(context -> context.securityContextRepository(securityContexts))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new LoginUrlAuthenticationEntryPoint(properties.loginUri().toString())
                ))
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServer.getEndpointsMatcher()))
                .addFilterBefore(
                        new OAuthTokenEndpointTransactionFilter(transactionManager),
                        SecurityContextHolderFilter.class
                );
        return http.build();
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(SessionProperties properties) {
        return AuthorizationServerSettings.builder()
                .issuer(properties.issuer().toString())
                .build();
    }

    @Bean
    PasswordEncoder oauthClientPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> oauthTokenClaimsCustomizer(
            PasswordlessIdentityOperations identities,
            SessionProperties properties,
            Clock clock,
            JpaSigningKeyStore signingKeys,
            SessionRepository sessions,
            SessionSecurityMetrics metrics
    ) {
        return new OAuthTokenClaimsCustomizer(
                identities,
                properties,
                clock,
                signingKeys,
                sessions,
                metrics
        );
    }
}
