package com.vkplatform.iam.session.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties("iam.oauth.clients")
public record OAuthClientProperties(
        String serviceClientId,
        String serviceBootstrapSecret,
        String serviceAudience,
        String serviceScopes
) {
    public static final Set<String> REQUIRED_SERVICE_SCOPES = Set.of(
            "iam.authorization.check",
            "iam.relationship.write"
    );

    public OAuthClientProperties {
        requireToken(serviceClientId, "IAM_SERVICE_CLIENT_ID");
        requireToken(serviceAudience, "IAM_OAUTH_SERVICE_AUDIENCE");
        if (!parseScopes(serviceScopes).equals(REQUIRED_SERVICE_SCOPES)) {
            throw new IllegalStateException(
                    "IAM_SERVICE_CLIENT_SCOPES must contain exactly the required service scopes"
            );
        }
    }

    public Set<String> parsedServiceScopes() {
        return parseScopes(serviceScopes);
    }

    private static Set<String> parseScopes(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(configured.split(","))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void requireToken(String value, String name) {
        if (value == null
                || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalStateException(name + " is invalid");
        }
    }
}
