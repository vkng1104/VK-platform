package com.vkplatform.iam.session.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.Assert;

import java.time.Duration;

final class OAuthCacheControlHeadersWriter implements HeaderWriter {
    private final RequestMatcher jwkSetRequest;
    private final String jwkSetCacheControl;
    private final HeaderWriter sensitiveEndpointCacheControl = new CacheControlHeadersWriter();

    OAuthCacheControlHeadersWriter(String jwkSetEndpointUri, Duration jwkSetCacheTtl) {
        Assert.hasText(jwkSetEndpointUri, "JWK Set endpoint URI cannot be empty");
        Assert.notNull(jwkSetCacheTtl, "JWK Set cache TTL cannot be null");
        this.jwkSetRequest = PathPatternRequestMatcher.withDefaults()
                .matcher(HttpMethod.GET, jwkSetEndpointUri);
        this.jwkSetCacheControl = CacheControl.maxAge(jwkSetCacheTtl)
                .cachePublic()
                .mustRevalidate()
                .getHeaderValue();
    }

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (jwkSetRequest.matches(request)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, jwkSetCacheControl);
            return;
        }
        sensitiveEndpointCacheControl.writeHeaders(request, response);
    }
}
