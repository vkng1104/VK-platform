package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.infrastructure.persistence.repository.JpaSigningKeyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("signingKeyHealthIndicator")
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public final class SigningKeyHealthIndicator implements HealthIndicator {
    private final JpaSigningKeyStore signingKeys;

    public SigningKeyHealthIndicator(JpaSigningKeyStore signingKeys) {
        this.signingKeys = signingKeys;
    }

    @Override
    public Health health() {
        if (!signingKeys.hasUsableActiveKey()) {
            return Health.down()
                    .withDetail("reason", "No usable active signing key. Run the explicit bootstrap operation.")
                    .build();
        }
        return Health.up().build();
    }
}
