package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthClientPropertiesTest {
    @Test
    void acceptsOnlyTheRequiredServiceScopes() {
        OAuthClientProperties properties = properties(
                " iam.relationship.write, iam.authorization.check "
        );

        assertThat(properties.parsedServiceScopes())
                .containsExactlyInAnyOrder("iam.authorization.check", "iam.relationship.write");
    }

    @Test
    void rejectsInvalidIdentifiersAudiencesAndScopeExpansion() {
        assertThatThrownBy(() -> new OAuthClientProperties(
                "contains spaces",
                "s".repeat(32),
                "vk-platform-services",
                requiredScopes(),
                "",
                "",
                "",
                "",
                "",
                null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_SERVICE_CLIENT_ID");
        assertThatThrownBy(() -> new OAuthClientProperties(
                "vk-platform-service",
                "s".repeat(32),
                "",
                requiredScopes(),
                "",
                "",
                "",
                "",
                "",
                null
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_OAUTH_SERVICE_AUDIENCE");
        assertThatThrownBy(() -> properties(requiredScopes() + ",api.access"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAM_SERVICE_CLIENT_SCOPES");
    }

    private static OAuthClientProperties properties(String scopes) {
        return new OAuthClientProperties(
                "vk-platform-service",
                "s".repeat(32),
                "vk-platform-services",
                scopes,
                "",
                "",
                "",
                "",
                "",
                Instant.now().plusSeconds(3600)
        );
    }

    private static String requiredScopes() {
        return "iam.authorization.check,iam.relationship.write";
    }
}
