package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class SessionSecurityConfiguration {
    @Bean
    SecurityContextRepository browserSecurityContextRepository(
            AuthenticationSessionRepository sessions,
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
    SecurityFilterChain authenticationGrantExchangeSecurityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContexts,
            SessionProperties properties
    ) throws Exception {
        BffSessionOperation operation = BffSessionOperation.EXCHANGE_AUTHENTICATION_GRANT;
        return http
                .securityMatcher(operation.path())
                .csrf(csrf -> csrf.ignoringRequestMatchers(operation.path()))
                .securityContext(context -> context
                        .securityContextRepository(securityContexts)
                        .requireExplicitSave(true)
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .addFilterBefore(
                        new BffClientAuthenticationFilter(properties, operation),
                        AnonymousAuthenticationFilter.class
                )
                .build();
    }
}
