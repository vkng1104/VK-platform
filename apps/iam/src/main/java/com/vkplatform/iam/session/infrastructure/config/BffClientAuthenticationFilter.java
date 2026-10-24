package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.platform.web.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

final class BffClientAuthenticationFilter extends OncePerRequestFilter {
    private final SessionProperties properties;
    private final BffSessionOperation operation;

    BffClientAuthenticationFilter(
            SessionProperties properties,
            BffSessionOperation operation
    ) {
        this.properties = properties;
        this.operation = operation;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!operation.method().equals(request.getMethod()) || !operation.path().equals(request.getRequestURI())) {
            reject(response, request, HttpServletResponse.SC_FORBIDDEN, "BFF_OPERATION_NOT_ALLOWED");
            return;
        }
        if (!trustedBrowserContext(request)) {
            reject(response, request, HttpServletResponse.SC_FORBIDDEN, "UNTRUSTED_REQUEST_ORIGIN");
            return;
        }
        ClientCredential credential = credential(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (credential == null
                || !constantTimeEquals(properties.bffClientId(), credential.clientId())
                || !constantTimeEquals(properties.bffClientSecret(), credential.secret())) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"iam-bff\"");
            reject(response, request, HttpServletResponse.SC_UNAUTHORIZED, "BFF_AUTHENTICATION_REQUIRED");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean trustedBrowserContext(HttpServletRequest request) {
        if ("cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"))) {
            return false;
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || origin.isBlank()) {
            return true;
        }
        try {
            URI supplied = URI.create(origin);
            URI trusted = properties.bffOrigin();
            return supplied.getScheme() != null
                    && supplied.getHost() != null
                    && supplied.getScheme().equalsIgnoreCase(trusted.getScheme())
                    && supplied.getHost().equalsIgnoreCase(trusted.getHost())
                    && effectivePort(supplied) == effectivePort(trusted)
                    && (supplied.getPath().isEmpty() || "/".equals(supplied.getPath()))
                    && supplied.getQuery() == null
                    && supplied.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static int effectivePort(URI value) {
        if (value.getPort() >= 0) {
            return value.getPort();
        }
        return "https".equalsIgnoreCase(value.getScheme()) ? 443 : 80;
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static ClientCredential credential(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        try {
            String decoded = new String(
                    Base64.getDecoder().decode(authorization.substring(6).strip()),
                    StandardCharsets.UTF_8
            );
            int separator = decoded.indexOf(':');
            if (separator <= 0 || separator == decoded.length() - 1) {
                return null;
            }
            return new ClientCredential(decoded.substring(0, separator), decoded.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void reject(
            HttpServletResponse response,
            HttpServletRequest request,
            int status,
            String code
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\","
                + "\"message\":\"The trusted BFF request could not be authenticated.\","
                + "\"request_id\":\"" + RequestContext.requestId(request) + "\","
                + "\"retryable\":false}");
    }

    private record ClientCredential(String clientId, String secret) {
    }
}
