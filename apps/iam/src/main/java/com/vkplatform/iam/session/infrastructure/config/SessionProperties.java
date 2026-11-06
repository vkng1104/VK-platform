package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.domain.SessionPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("iam.session")
public record SessionProperties(
        boolean enabled,
        String bffClientId,
        String bffClientSecret,
        URI bffOrigin,
        Duration absoluteTtl,
        String browserCookieName,
        boolean browserCookieSecure
) {
    public SessionProperties {
        if (enabled) {
            new SessionPolicy(bffClientId, absoluteTtl);
            if (bffClientSecret == null || bffClientSecret.length() < 32) {
                throw new IllegalStateException("IAM_BFF_CLIENT_SECRET must contain at least 32 characters");
            }
            if (bffOrigin == null || !bffOrigin.isAbsolute() || bffOrigin.getHost() == null
                    || bffOrigin.getQuery() != null || bffOrigin.getFragment() != null
                    || !(bffOrigin.getPath().isEmpty() || "/".equals(bffOrigin.getPath()))) {
                throw new IllegalStateException("IAM_BFF_ORIGIN must be an absolute origin");
            }
            if (browserCookieName == null || browserCookieName.isBlank()
                    || browserCookieName.length() > 128
                    || !browserCookieName.equals(browserCookieName.strip())) {
                throw new IllegalStateException("IAM_SESSION_COOKIE_NAME is invalid");
            }
        }
    }
}
