package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.OAuthClientReadiness;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("oauthClientHealthIndicator")
@ConditionalOnProperty(name = "iam.oauth.enabled", havingValue = "true")
public final class OAuthClientHealthIndicator implements HealthIndicator {
    private final OAuthClientReadiness clients;

    public OAuthClientHealthIndicator(OAuthClientReadiness clients) {
        this.clients = clients;
    }

    @Override
    public Health health() {
        OAuthClientReadiness.Readiness readiness = clients.readiness();
        if (!readiness.ready()) {
            return Health.down()
                    .withDetail("reason", readiness.reason())
                    .withDetail("action", "run_explicit_oauth_client_reconciliation")
                    .build();
        }
        return Health.up().build();
    }
}
