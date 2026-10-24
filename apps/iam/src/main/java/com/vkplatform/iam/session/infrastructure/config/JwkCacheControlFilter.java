package com.vkplatform.iam.session.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public final class JwkCacheControlFilter extends OncePerRequestFilter {
    private static final PathPatternRequestMatcher JWKS =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/oauth2/jwks");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !JWKS.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        filterChain.doFilter(request, response);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "public, max-age=30, must-revalidate");
    }
}
