package com.vkplatform.iam.session.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthCacheControlHeadersWriterTest {
    private final OAuthCacheControlHeadersWriter writer = new OAuthCacheControlHeadersWriter(
            "/oauth2/jwks",
            Duration.ofMinutes(5)
    );

    @Test
    void allowsPublicCachingOnlyForTheJwkSetEndpoint() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writeHeaders(new MockHttpServletRequest("GET", "/oauth2/jwks"), response);

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .contains("max-age=300", "public", "must-revalidate");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isNull();
        assertThat(response.getHeader(HttpHeaders.EXPIRES)).isNull();
    }

    @Test
    void preventsCachingForSensitiveOAuthEndpoints() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.writeHeaders(new MockHttpServletRequest("POST", "/oauth2/token"), response);

        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeader(HttpHeaders.EXPIRES)).isEqualTo("0");
    }
}
