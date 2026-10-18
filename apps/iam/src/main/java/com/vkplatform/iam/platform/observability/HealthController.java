package com.vkplatform.iam.platform.observability;

import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final HealthEndpoint healthEndpoint;
    private final boolean sessionsEnabled;

    public HealthController(
            HealthEndpoint healthEndpoint,
            @Value("${iam.session.enabled:false}") boolean sessionsEnabled
    ) {
        this.healthEndpoint = healthEndpoint;
        this.sessionsEnabled = sessionsEnabled;
    }

    @GetMapping("/healthz")
    Map<String, String> health() {
        return Map.of("status", "ok", "service", "iam");
    }

    @GetMapping("/readyz")
    Map<String, String> readiness() {
        HealthDescriptor databaseHealth = healthEndpoint.healthForPath("db");
        if (databaseHealth == null || !Status.UP.equals(databaseHealth.getStatus())) {
            throw new IllegalStateException("IAM database is not ready");
        }
        if (sessionsEnabled) {
            HealthDescriptor signingKeyHealth = healthEndpoint.healthForPath("signingKey");
            if (signingKeyHealth == null || !Status.UP.equals(signingKeyHealth.getStatus())) {
                throw new IllegalStateException("IAM signing key is not ready");
            }
            HealthDescriptor clientHealth = healthEndpoint.healthForPath("oauthClient");
            if (clientHealth == null || !Status.UP.equals(clientHealth.getStatus())) {
                throw new IllegalStateException("IAM OAuth clients are not ready");
            }
        }
        return Map.of("status", "ready", "service", "iam");
    }
}
