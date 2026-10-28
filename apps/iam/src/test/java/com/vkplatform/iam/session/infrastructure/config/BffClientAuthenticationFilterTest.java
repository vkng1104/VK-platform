package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.platform.web.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BffClientAuthenticationFilterTest {
    private static final String SECRET = "trusted-bff-secret-with-32-characters";

    private BffClientAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new BffClientAuthenticationFilter(
                new SessionProperties(
                        true,
                        "vk-platform-bff",
                        SECRET,
                        URI.create("https://web.example.test"),
                        Duration.ofDays(30)
                ),
                BffSessionOperation.EXCHANGE_AUTHENTICATION_GRANT
        );
    }

    @Test
    void acceptsTheConfiguredBffFromTrustedAndServerSideContexts() throws Exception {
        for (String origin : new String[]{"https://web.example.test", null}) {
            MockHttpServletRequest request = request(
                    origin,
                    "same-origin",
                    basic("vk-platform-bff", SECRET)
            );
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(request, new MockHttpServletResponse(),
                    (current, response) -> invoked.set(true));

            assertThat(invoked).as("origin %s", origin).isTrue();
        }
    }

    @Test
    void rejectsMissingOrInvalidBffCredentials() throws Exception {
        for (String authorization : new String[]{
                null,
                "Basic not-base64",
                basic("vk-platform-bff", "wrong-secret")
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(
                    request("https://web.example.test", "same-origin", authorization),
                    response,
                    (current, currentResponse) -> {
                        throw new AssertionError("unauthenticated request reached the endpoint");
                    }
            );

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                    .isEqualTo("Basic realm=\"iam-bff\"");
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(response.getContentAsString()).contains("BFF_AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void rejectsCrossSiteUntrustedAndUnsupportedOperations() throws Exception {
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{
                request("https://attacker.example", "same-origin", basic("vk-platform-bff", SECRET)),
                request("https://web.example.test", "cross-site", basic("vk-platform-bff", SECRET)),
                request("https://web.example.test", "same-origin", basic("vk-platform-bff", SECRET), "GET")
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (current, currentResponse) -> {
                throw new AssertionError("untrusted request reached the endpoint");
            });

            assertThat(response.getStatus()).isEqualTo(403);
        }
    }

    private static MockHttpServletRequest request(
            String origin,
            String fetchSite,
            String authorization
    ) {
        return request(origin, fetchSite, authorization, "POST");
    }

    private static MockHttpServletRequest request(
            String origin,
            String fetchSite,
            String authorization,
            String method
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                method,
                "/api/v1/authentication/grants/exchange"
        );
        if (origin != null) {
            request.addHeader(HttpHeaders.ORIGIN, origin);
        }
        request.addHeader("Sec-Fetch-Site", fetchSite);
        if (authorization != null) {
            request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        }
        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, "bff-filter-test");
        return request;
    }

    private static String basic(String clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (clientId + ":" + secret).getBytes(StandardCharsets.UTF_8)
        );
    }
}
