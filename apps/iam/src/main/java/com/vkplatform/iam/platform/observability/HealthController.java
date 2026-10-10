package com.vkplatform.iam.platform.observability;

import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final HealthEndpoint healthEndpoint;

    public HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
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
        return Map.of("status", "ready", "service", "iam");
    }
}
