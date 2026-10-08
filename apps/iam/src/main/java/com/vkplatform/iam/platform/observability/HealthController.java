package com.vkplatform.iam.platform.observability;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/healthz")
    Map<String, String> health() {
        return Map.of("status", "ok", "service", "iam");
    }

    @GetMapping("/readyz")
    Map<String, String> readiness() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "ready", "service", "iam");
    }
}
