package com.vkplatform.iam.identity.infrastructure.config;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.application.port.out.GrantTokenCodec;
import com.vkplatform.iam.identity.application.port.out.PasswordlessIdentityRepository;
import com.vkplatform.iam.identity.application.service.PasswordlessIdentityService;
import com.vkplatform.iam.identity.domain.IdentityPolicy;
import com.vkplatform.iam.identity.infrastructure.crypto.SecureGrantTokenCodec;
import com.vkplatform.iam.verification.api.EmailVerificationOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {
    @Bean
    IdentityPolicy identityPolicy(IdentityProperties properties) {
        return new IdentityPolicy(properties.audience(), properties.grantTtl());
    }

    @Bean
    GrantTokenCodec grantTokenCodec() {
        return new SecureGrantTokenCodec(new SecureRandom());
    }

    @Bean
    @ConditionalOnProperty(name = "iam.email.provider", havingValue = "gmail")
    PasswordlessIdentityOperations passwordlessIdentityOperations(
            EmailVerificationOperations verification,
            PasswordlessIdentityRepository repository,
            GrantTokenCodec grants,
            Clock clock,
            IdentityPolicy policy
    ) {
        return new PasswordlessIdentityService(verification, repository, grants, clock, policy);
    }
}
