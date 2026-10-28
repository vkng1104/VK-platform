package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthPropertiesTest {
    @Test
    void acceptsCompleteEnabledSigningConfiguration() {
        OAuthProperties properties = properties("oauth-signing-2026", Duration.ofMinutes(5));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.signingKeyId()).isEqualTo("oauth-signing-2026");
        assertThat(properties.jwksCacheTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void rejectsInvalidEnabledSigningConfiguration() {
        assertThatThrownBy(() -> properties("invalid key id", Duration.ofMinutes(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_SIGNING_KEY_ID");
        assertThatThrownBy(() -> new OAuthProperties(
                true,
                "oauth-signing-2026",
                "",
                "public-key",
                Duration.ofMinutes(5)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_SIGNING_PRIVATE_KEY_PKCS8_BASE64");
        assertThatThrownBy(() -> properties("oauth-signing-2026", Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_JWKS_CACHE_TTL");
    }

    @Test
    void permitsEmptyValuesWhileOAuthIsDisabled() {
        OAuthProperties properties = new OAuthProperties(false, null, null, null, null);

        assertThat(properties.enabled()).isFalse();
    }

    private static OAuthProperties properties(String keyId, Duration cacheTtl) {
        return new OAuthProperties(true, keyId, "private-key", "public-key", cacheTtl);
    }
}
