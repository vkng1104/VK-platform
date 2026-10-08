package com.vkplatform.iam.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain iamSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(
                                "/healthz",
                                "/readyz",
                                "/api/docs/**",
                                "/api/v1/email-verifications/**"
                        ).permitAll()
                        .anyRequest().denyAll()
                )
                .build();
    }
}
