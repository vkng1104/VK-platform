package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.application.port.out.SessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class SessionSecurityConfiguration {
    @Bean
    SecurityContextRepository browserSecurityContextRepository(
            SessionRepository sessions,
            SessionProperties properties,
            Clock clock
    ) {
        return new DatabaseValidatingSecurityContextRepository(
                new HttpSessionSecurityContextRepository(),
                sessions,
                properties.bffClientId(),
                clock
        );
    }

    @Bean
    SessionAuthenticationStrategy browserSessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    @Order(2)
    SecurityFilterChain passwordlessSessionExchangeSecurityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContexts,
            RegisteredClientRepository clients,
            PasswordEncoder passwords,
            SessionProperties properties
    ) throws Exception {
        return http
                .securityMatcher("/api/v1/authentication/grants/exchange")
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/authentication/grants/exchange"))
                .securityContext(context -> context
                        .securityContextRepository(securityContexts)
                        .requireExplicitSave(true)
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .addFilterBefore(
                        new BffClientAuthenticationFilter(clients, passwords, properties),
                        AnonymousAuthenticationFilter.class
                )
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain sessionManagementSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(
                        "/api/v1/authentication/session",
                        "/api/v1/authentication/sessions/**",
                        "/api/v1/authentication/logout"
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
