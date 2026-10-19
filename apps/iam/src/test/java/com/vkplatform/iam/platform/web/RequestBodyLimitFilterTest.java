package com.vkplatform.iam.platform.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyLimitFilterTest {
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter();

    @Test
    void acceptsExactlyTheLimitAndMatchesBehindAContextPath() throws Exception {
        byte[] body = new byte[RequestBodyLimitFilter.JSON_LIMIT];
        MockHttpServletRequest request = request("/api/v1/authentication/challenges", body);
        request.setContextPath("/iam");
        request.setRequestURI("/iam/api/v1/authentication/challenges");
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (current, response) -> {
            invoked.set(true);
            assertThat(current.getInputStream().readAllBytes()).hasSize(body.length);
        });

        assertThat(invoked).isTrue();
    }

    @Test
    void rejectsDeclaredAndMisdeclaredBodiesOneByteOverTheLimit() throws Exception {
        MockHttpServletResponse declaredResponse = new MockHttpServletResponse();
        MockHttpServletRequest declared = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return RequestBodyLimitFilter.JSON_LIMIT + 1L;
            }
        };
        configure(declared, "/api/v1/authentication/challenges", new byte[1]);

        filter.doFilter(declared, declaredResponse, (request, response) -> {
            throw new AssertionError("declared oversized body reached the application");
        });
        assertPayloadTooLarge(declaredResponse);

        MockHttpServletResponse streamedResponse = new MockHttpServletResponse();
        MockHttpServletRequest streamed = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return 1;
            }
        };
        configure(
                streamed,
                "/api/v1/authentication/challenges",
                new byte[RequestBodyLimitFilter.JSON_LIMIT + 1]
        );

        filter.doFilter(streamed, streamedResponse, (request, response) -> {
            throw new AssertionError("streamed oversized body reached the application");
        });
        assertPayloadTooLarge(streamedResponse);
    }

    @Test
    void boundsAndReplaysOAuthFormParameters() throws Exception {
        byte[] body = "grant_type=refresh_token&refresh_token=opaque-value"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request("/oauth2/token", body);
        request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);

        filter.doFilter(request, new MockHttpServletResponse(), (current, response) -> {
            assertThat(current.getParameter("grant_type")).isEqualTo("refresh_token");
            assertThat(current.getParameter("refresh_token")).isEqualTo("opaque-value");
        });

        MockHttpServletResponse oversizedResponse = new MockHttpServletResponse();
        filter.doFilter(
                request("/oauth2/token", new byte[RequestBodyLimitFilter.OAUTH_FORM_LIMIT + 1]),
                oversizedResponse,
                (current, response) -> {
                    throw new AssertionError("oversized OAuth form reached the token endpoint");
                }
        );
        assertPayloadTooLarge(oversizedResponse);
    }

    @Test
    void leavesUnrelatedRoutesUntouched() throws Exception {
        MockHttpServletRequest request = request("/api/v1/unrelated", new byte[16_384]);
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (current, response) -> invoked.set(true));

        assertThat(invoked).isTrue();
    }

    private static MockHttpServletRequest request(String path, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        configure(request, path, body);
        return request;
    }

    private static void configure(MockHttpServletRequest request, String path, byte[] body) {
        request.setMethod("POST");
        request.setRequestURI(path);
        request.setServletPath(path);
        request.setContent(body);
        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, "request-body-test");
    }

    private static void assertPayloadTooLarge(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString())
                .contains("REQUEST_BODY_TOO_LARGE")
                .contains("request-body-test");
    }
}
