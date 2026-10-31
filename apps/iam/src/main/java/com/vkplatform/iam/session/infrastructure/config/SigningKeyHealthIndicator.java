package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyRuntime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("signingKeyHealthIndicator")
@ConditionalOnProperty(name = "iam.oauth.enabled", havingValue = "true")
public final class SigningKeyHealthIndicator implements HealthIndicator {
    private final SigningKeyRuntime signingKeys;

    public SigningKeyHealthIndicator(SigningKeyRuntime signingKeys) {
        this.signingKeys = signingKeys;
    }

    @Override
    public Health health() {
        SigningKeyRuntime.Readiness readiness = signingKeys.readiness();
        if (!readiness.usable()) {
            return Health.down()
                    .withDetail("reason", readiness.reason())
                    .withDetail("action", "run_explicit_signing_key_operation")
                    .build();
        }
        return Health.up().build();
    }
}
