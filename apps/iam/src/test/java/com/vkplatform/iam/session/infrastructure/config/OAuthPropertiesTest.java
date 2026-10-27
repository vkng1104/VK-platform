package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthPropertiesTest {
    @Test
    void acceptsCompleteEnabledSigningConfiguration() {
        OAuthProperties properties = properties("oauth-signing-2026");

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.signingKeyId()).isEqualTo("oauth-signing-2026");
    }

    @Test
    void rejectsInvalidEnabledSigningConfiguration() {
        assertThatThrownBy(() -> properties("invalid key id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_SIGNING_KEY_ID");
        assertThatThrownBy(() -> new OAuthProperties(
                true,
                "oauth-signing-2026",
                "",
                "public-key"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_SIGNING_PRIVATE_KEY_PKCS8_BASE64");
    }

    @Test
    void permitsEmptyValuesWhileOAuthIsDisabled() {
        OAuthProperties properties = new OAuthProperties(false, null, null, null);

        assertThat(properties.enabled()).isFalse();
    }

    private static OAuthProperties properties(String keyId) {
        return new OAuthProperties(true, keyId, "private-key", "public-key");
    }
}
