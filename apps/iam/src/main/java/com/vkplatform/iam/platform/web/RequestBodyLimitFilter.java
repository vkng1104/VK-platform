package com.vkplatform.iam.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    static final int SMALL_JSON_LIMIT = 1024;
    static final int JSON_LIMIT = 4 * 1024;
    static final int OAUTH_FORM_LIMIT = 8 * 1024;

    private static final List<LimitRule> RULES = List.of(
            rule("/api/v1/authentication/challenges", JSON_LIMIT),
            rule("/api/v1/authentication/challenges/*/verify", SMALL_JSON_LIMIT),
            rule("/api/v1/email-verifications", JSON_LIMIT),
            rule("/api/v1/email-verifications/*/verify", SMALL_JSON_LIMIT),
            rule("/api/v1/authentication/grants/exchange", JSON_LIMIT),
            rule("/oauth2/token", OAUTH_FORM_LIMIT)
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return bodyLimit(request).isEmpty();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        int limit = bodyLimit(request).orElseThrow();
        try {
            if (request.getContentLengthLong() > limit) {
                throw new PayloadTooLargeException();
            }
            byte[] body = request.getInputStream().readNBytes(limit + 1);
            if (body.length > limit) {
                throw new PayloadTooLargeException();
            }
            filterChain.doFilter(new CachedBodyRequest(request, body), response);
        } catch (PayloadTooLargeException exception) {
            writePayloadTooLarge(response, RequestContext.requestId(request));
        }
    }

    private static OptionalInt bodyLimit(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return OptionalInt.empty();
        }
        return RULES.stream()
                .filter(rule -> rule.matcher().matches(request))
                .mapToInt(LimitRule::bytes)
                .findFirst();
    }

    private static LimitRule rule(String pattern, int bytes) {
        return new LimitRule(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, pattern), bytes);
    }

    private static void writePayloadTooLarge(HttpServletResponse response, String requestId)
            throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"REQUEST_BODY_TOO_LARGE\","
                + "\"message\":\"The request body is too large.\","
                + "\"request_id\":\"" + requestId + "\","
                + "\"retryable\":false}");
    }

    private record LimitRule(RequestMatcher matcher, int bytes) {
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final Map<String, String[]> parameters;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
            this.parameters = parseParameters(request, body);
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ByteArrayServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = parameters.get(name);
            return values == null ? null : values.clone();
        }

        private static Map<String, String[]> parseParameters(HttpServletRequest request, byte[] body) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            addEncoded(values, request.getQueryString());
            if (request.getContentType() != null
                    && request.getContentType().startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)) {
                addEncoded(values, new String(body, StandardCharsets.UTF_8));
            }
            request.getParameterMap().forEach((name, items) ->
                    values.putIfAbsent(name, new ArrayList<>(List.of(items))));
            Map<String, String[]> result = new LinkedHashMap<>();
            values.forEach((name, items) -> result.put(name, items.toArray(String[]::new)));
            return Collections.unmodifiableMap(result);
        }

        private static void addEncoded(Map<String, List<String>> values, String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return;
            }
            for (String pair : encoded.split("&")) {
                int separator = pair.indexOf('=');
                String name = decode(separator < 0 ? pair : pair.substring(0, separator));
                String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
                values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        private ByteArrayServletInputStream(byte[] body) {
            delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("async request-body reads are not supported");
        }
    }
}
