package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.OAuthClientReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthClientHealthIndicatorTest {
    @Test
    void reportsReadinessWithoutLeakingCredentials() {
        OAuthClientReadiness readiness = mock(OAuthClientReadiness.class);
        OAuthClientHealthIndicator indicator = new OAuthClientHealthIndicator(readiness);
        when(readiness.readiness()).thenReturn(new OAuthClientReadiness.Readiness(true, null));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        when(readiness.readiness()).thenReturn(new OAuthClientReadiness.Readiness(
                false,
                "required_oauth_client_missing"
        ));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails())
                .containsEntry("reason", "required_oauth_client_missing")
                .containsEntry("action", "run_explicit_oauth_client_reconciliation")
                .doesNotContainKeys("client_secret", "exception");
    }
}
