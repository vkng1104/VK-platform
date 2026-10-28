package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionPropertiesTest {
    private static final String SECRET = "trusted-bff-secret-with-32-characters";

    @Test
    void validatesEnabledExchangeConfiguration() {
        SessionProperties properties = properties(SECRET, URI.create("https://web.example.test"));

        assertThat(properties.enabled()).isTrue();
        assertThat(BffSessionOperation.values())
                .containsExactly(BffSessionOperation.EXCHANGE_AUTHENTICATION_GRANT);
    }

    @Test
    void rejectsWeakSecretsAndNonOriginUris() {
        assertThatThrownBy(() -> properties("short", URI.create("https://web.example.test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_BFF_CLIENT_SECRET");
        assertThatThrownBy(() -> properties(SECRET, URI.create("https://web.example.test/path")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_BFF_ORIGIN");
    }

    @Test
    void permitsEmptyValuesWhileTheFeatureIsDisabled() {
        SessionProperties properties = new SessionProperties(false, null, null, null, null);

        assertThat(properties.enabled()).isFalse();
    }

    private static SessionProperties properties(String secret, URI origin) {
        return new SessionProperties(
                true,
                "vk-platform-bff",
                secret,
                origin,
                Duration.ofDays(30)
        );
    }
}
