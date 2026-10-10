package com.vkplatform.iam.platform.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    private final HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
    private final IndicatedHealthDescriptor databaseHealth = mock(IndicatedHealthDescriptor.class);
    private final HealthController controller = new HealthController(healthEndpoint);

    @Test
    void reportsReadyFromTheStandardDatabaseHealthContributor() {
        when(healthEndpoint.healthForPath("db")).thenReturn(databaseHealth);
        when(databaseHealth.getStatus()).thenReturn(Status.UP);

        assertThat(controller.readiness()).isEqualTo(Map.of("status", "ready", "service", "iam"));
        verify(healthEndpoint).healthForPath("db");
    }

    @Test
    void rejectsReadinessWhenTheDatabaseContributorIsMissingOrDown() {
        when(healthEndpoint.healthForPath("db")).thenReturn(null);
        assertThatThrownBy(controller::readiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("IAM database is not ready");

        when(healthEndpoint.healthForPath("db")).thenReturn(databaseHealth);
        when(databaseHealth.getStatus()).thenReturn(Status.DOWN);
        assertThatThrownBy(controller::readiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("IAM database is not ready");
    }
}
