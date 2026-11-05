package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.application.port.out.OAuthAuthorizationRepository;
import com.vkplatform.iam.session.application.port.out.OAuthRefreshTokenRepository;
import com.vkplatform.iam.session.application.service.OAuthTokenLifecycleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Gated like the OAuth authorization store, which is the only caller. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = {"iam.oauth.enabled", "iam.session.enabled"},
        havingValue = "true"
)
public class OAuthTokenLifecycleConfiguration {
    @Bean
    OAuthTokenLifecycleService oauthTokenLifecycleService(
            AuthenticationSessionRepository sessions,
            OAuthAuthorizationRepository authorizations,
            OAuthRefreshTokenRepository refreshTokens,
            PasswordlessIdentityOperations identities,
            Clock clock
    ) {
        return new OAuthTokenLifecycleService(sessions, authorizations, refreshTokens, identities, clock);
    }
}
