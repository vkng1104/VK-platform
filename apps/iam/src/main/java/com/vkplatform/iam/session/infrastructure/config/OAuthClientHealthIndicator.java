package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.infrastructure.persistence.repository.JpaOAuthClientRegistrationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("oauthClientHealthIndicator")
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public final class OAuthClientHealthIndicator implements HealthIndicator {
    private final JpaOAuthClientRegistrationService clients;

    public OAuthClientHealthIndicator(JpaOAuthClientRegistrationService clients) {
        this.clients = clients;
    }

    @Override
    public Health health() {
        if (!clients.requiredClientsReady()) {
            return Health.down()
                    .withDetail("reason", "Required OAuth clients are missing or configuration has drifted.")
                    .build();
        }
        return Health.up().build();
    }
}
