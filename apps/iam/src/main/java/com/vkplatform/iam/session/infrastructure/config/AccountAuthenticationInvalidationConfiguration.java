package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.application.port.out.OAuthAuthorizationRepository;
import com.vkplatform.iam.session.application.service.AccountAuthenticationInvalidationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Not gated by {@code iam.session.enabled}: an account status change must end stored sessions
 * and authorizations even while the session endpoints are switched off.
 */
@Configuration(proxyBeanMethods = false)
public class AccountAuthenticationInvalidationConfiguration {
    @Bean
    AccountAuthenticationInvalidationService accountAuthenticationInvalidationService(
            AuthenticationSessionRepository sessions,
            OAuthAuthorizationRepository authorizations
    ) {
        return new AccountAuthenticationInvalidationService(sessions, authorizations);
    }
}
