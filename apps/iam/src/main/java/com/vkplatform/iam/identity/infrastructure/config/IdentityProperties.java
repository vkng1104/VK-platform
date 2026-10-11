package com.vkplatform.iam.identity.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("iam.authentication")
public record IdentityProperties(
        String audience,
        Duration grantTtl
) {
}
