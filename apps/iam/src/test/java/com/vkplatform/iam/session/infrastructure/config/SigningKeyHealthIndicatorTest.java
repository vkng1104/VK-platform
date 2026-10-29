package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SigningKeyHealthIndicatorTest {
    @Test
    void reportsUpOnlyForADecryptableConsistentActiveKey() {
        SigningKeyRuntime store = mock(SigningKeyRuntime.class);
        SigningKeyHealthIndicator indicator = new SigningKeyHealthIndicator(store);
        when(store.readiness()).thenReturn(new SigningKeyRuntime.Readiness(true, null));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        when(store.readiness()).thenReturn(new SigningKeyRuntime.Readiness(
                false,
                "active_signing_key_unusable"
        ));
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails())
                .containsEntry("reason", "active_signing_key_unusable")
                .doesNotContainKeys("exception", "key_material");
    }
}
