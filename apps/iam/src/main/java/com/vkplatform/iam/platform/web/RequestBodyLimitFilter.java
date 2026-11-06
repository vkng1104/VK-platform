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
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

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

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class RequestBodyLimitFilter extends OncePerRequestFilter {
    static final int SMALL_JSON_LIMIT = 1024;
    static final int BOUNDED_JSON_LIMIT = 4 * 1024;
    static final int OAUTH_FORM_LIMIT = 8 * 1024;

    private final ObjectMapper objectMapper;

    public RequestBodyLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        int limit = bodyLimit(request);
        if (limit == 0) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getContentLengthLong() > limit) {
            reject(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            reject(request, response);
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
        objectMapper.writeValue(response.getOutputStream(), new ApiError(
                "REQUEST_BODY_TOO_LARGE",
                "The request body is too large.",
                RequestContext.requestId(request),
                false,
                null
        ));
    }

    private static int bodyLimit(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return 0;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("/api/v1/email-verifications".equals(path)
                || "/api/v1/authentication/challenges".equals(path)) {
            return BOUNDED_JSON_LIMIT;
        }
        if ("/api/v1/authentication/grants/exchange".equals(path)
                || oneSegmentVerify(path, "/api/v1/email-verifications/")
                || oneSegmentVerify(path, "/api/v1/authentication/challenges/")) {
            return SMALL_JSON_LIMIT;
        }
        if ("/oauth2/token".equals(path)) {
            return OAUTH_FORM_LIMIT;
        }
        return 0;
    }

    private static boolean oneSegmentVerify(String path, String prefix) {
        if (!path.startsWith(prefix) || !path.endsWith("/verify")) {
            return false;
        }
        String identifier = path.substring(prefix.length(), path.length() - "/verify".length());
        return !identifier.isBlank() && identifier.indexOf('/') < 0;
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private final Map<String, String[]> formParameters;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
            this.formParameters = isForm(request)
                    ? parameters(request, body)
                    : null;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(
                    getInputStream(),
                    StandardCharsets.UTF_8
            ));
        }

        @Override
        public String getParameter(String name) {
            if (formParameters == null) {
                return super.getParameter(name);
            }
            String[] values = formParameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            if (formParameters == null) {
                return super.getParameterMap();
            }
            return formParameters;
        }

        @Override
        public Enumeration<String> getParameterNames() {
            if (formParameters == null) {
                return super.getParameterNames();
            }
            return Collections.enumeration(formParameters.keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            if (formParameters == null) {
                return super.getParameterValues(name);
            }
            String[] values = formParameters.get(name);
            return values == null ? null : values.clone();
        }

        private static boolean isForm(HttpServletRequest request) {
            String contentType = request.getContentType();
            return contentType != null
                    && contentType.toLowerCase(java.util.Locale.ROOT)
                    .startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        }

        private static Map<String, String[]> parameters(
                HttpServletRequest request,
                byte[] body
        ) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            append(values, request.getQueryString());
            append(values, new String(body, StandardCharsets.UTF_8));
            request.getParameterMap().forEach((name, entries) -> values.putIfAbsent(
                    name,
                    new ArrayList<>(List.of(entries))
            ));
            Map<String, String[]> result = new LinkedHashMap<>();
            values.forEach((name, entries) -> result.put(name, entries.toArray(String[]::new)));
            return Collections.unmodifiableMap(result);
        }

        private static void append(Map<String, List<String>> values, String encoded) {
            if (encoded == null || encoded.isEmpty()) {
                return;
            }
            for (String pair : encoded.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                String[] parts = pair.split("=", 2);
                String name = decode(parts[0]);
                String value = parts.length == 1 ? "" : decode(parts[1]);
                values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            }
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }

    private static final class CachedInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        private CachedInputStream(byte[] body) {
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
            throw new UnsupportedOperationException("asynchronous body reads are not supported");
        }
    }
}
