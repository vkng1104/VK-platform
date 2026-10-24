package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.session.api.AuthenticationSessionOperations;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.application.service.AuthenticationSessionService;
import com.vkplatform.iam.session.domain.SessionPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class AuthenticationSessionConfiguration {
    @Bean
    SessionPolicy sessionPolicy(SessionProperties properties) {
        return new SessionPolicy(properties.bffClientId(), properties.absoluteTtl());
    }

    @Bean
    AuthenticationSessionOperations authenticationSessionOperations(
            PasswordlessIdentityOperations identities,
            AuthenticationSessionRepository sessions,
            SessionPolicy policy,
            Clock clock
    ) {
        return new AuthenticationSessionService(identities, sessions, policy, clock);
    }
}
