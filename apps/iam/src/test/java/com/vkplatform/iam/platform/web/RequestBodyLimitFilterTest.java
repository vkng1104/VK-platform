package com.vkplatform.iam.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodyLimitFilterTest {
    private RequestBodyLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestBodyLimitFilter(JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void acceptsBodiesAtTheExactLimitAndReplaysTheBufferedBytes() throws Exception {
        byte[] body = "a".repeat(RequestBodyLimitFilter.SMALL_JSON_LIMIT)
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request(
                "/api/v1/authentication/grants/exchange",
                MediaType.APPLICATION_JSON_VALUE,
                body
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (filtered, ignored) -> assertThat(
                filtered.getInputStream().readAllBytes()
        ).containsExactly(body));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsDeclaredAndStreamedExcessWithAStableNoStoreError() throws Exception {
        byte[] body = "a".repeat(RequestBodyLimitFilter.SMALL_JSON_LIMIT + 1)
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest declared = request(
                "/api/v1/authentication/grants/exchange",
                MediaType.APPLICATION_JSON_VALUE,
                body
        );
        declared.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, "request-declared");
        MockHttpServletResponse declaredResponse = new MockHttpServletResponse();
        AtomicBoolean declaredChain = new AtomicBoolean();

        filter.doFilter(declared, declaredResponse, (filtered, ignored) -> declaredChain.set(true));

        assertThat(declaredChain).isFalse();
        assertThat(declaredResponse.getStatus()).isEqualTo(413);
        assertThat(declaredResponse.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(declaredResponse.getContentAsString())
                .contains("REQUEST_BODY_TOO_LARGE")
                .contains("request-declared");

        MockHttpServletRequest streamed = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        streamed.setMethod("POST");
        streamed.setRequestURI("/api/v1/authentication/grants/exchange");
        streamed.setContentType(MediaType.APPLICATION_JSON_VALUE);
        streamed.setContent(body);
        MockHttpServletResponse streamedResponse = new MockHttpServletResponse();

        filter.doFilter(streamed, streamedResponse, (filtered, ignored) -> {
            throw new AssertionError("streamed over-limit body reached the application");
        });

        assertThat(streamedResponse.getStatus()).isEqualTo(413);
    }

    @Test
    void preservesOAuthFormAndQueryParametersAfterBuffering() throws Exception {
        byte[] body = "grant_type=client_credentials&scope=iam.authorization.check%20iam.relationship.write"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request(
                "/oauth2/token",
                MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                body
        );
        request.setQueryString("trace=bounded&trace=preserved");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (filtered, ignored) -> {
            HttpServletRequest http = (HttpServletRequest) filtered;
            assertThat(http.getParameter("grant_type")).isEqualTo("client_credentials");
            assertThat(http.getParameter("scope"))
                    .isEqualTo("iam.authorization.check iam.relationship.write");
            assertThat(http.getParameterValues("trace")).containsExactly("bounded", "preserved");
            assertThat(http.getInputStream().readAllBytes()).containsExactly(body);
        });
    }

    @Test
    void preservesContainerParsedOAuthParametersWhenTheBodyIsAlreadyMaterialized() throws Exception {
        MockHttpServletRequest request = request(
                "/oauth2/token",
                MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                new byte[0]
        );
        request.addParameter("grant_type", "authorization_code");
        request.addParameter("code", "opaque-code");

        filter.doFilter(request, new MockHttpServletResponse(), (filtered, ignored) -> {
            HttpServletRequest http = (HttpServletRequest) filtered;
            assertThat(http.getParameter("grant_type")).isEqualTo("authorization_code");
            assertThat(http.getParameter("code")).isEqualTo("opaque-code");
        });
    }

    @Test
    void leavesUnrelatedMethodsAndNearMatchPathsUnlimited() throws Exception {
        byte[] body = "a".repeat(RequestBodyLimitFilter.BOUNDED_JSON_LIMIT + 100)
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = request(
                "/api/v1/email-verifications/id/verify/extra",
                MediaType.APPLICATION_JSON_VALUE,
                body
        );
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, new MockHttpServletResponse(), (filtered, ignored) -> {
            invoked.set(true);
            assertThat(filtered).isSameAs(request);
        });

        assertThat(invoked).isTrue();
    }

    private static MockHttpServletRequest request(
            String path,
            String contentType,
            byte[] body
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI(path);
        request.setContentType(contentType);
        request.setContent(body);
        return request;
    }
}
