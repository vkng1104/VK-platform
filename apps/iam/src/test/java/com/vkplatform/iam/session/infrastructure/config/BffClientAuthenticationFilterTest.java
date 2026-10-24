package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.platform.web.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.vkplatform.iam.session.SessionTestFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BffClientAuthenticationFilterTest {
    private static final String SECRET = "trusted-bff-secret-that-is-long";

    private BffClientAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
        RegisteredClient bff = RegisteredClient.withId("bff-id")
                .clientId("vk-platform-bff")
                .clientSecret(encoder.encode(SECRET))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://web.example.test/api/auth/callback")
                .build();
        when(clients.findByClientId("vk-platform-bff")).thenReturn(bff);
        filter = new BffClientAuthenticationFilter(clients, encoder, properties());
    }

    @Test
    void acceptsAuthenticatedBffFromTrustedOrServerSideContexts() throws Exception {
        for (String origin : new String[]{"https://web.example.test", null}) {
            MockHttpServletRequest request = request(origin, "same-origin", basic("vk-platform-bff", SECRET));
            AtomicBoolean invoked = new AtomicBoolean();

            filter.doFilter(request, new MockHttpServletResponse(),
                    (current, response) -> invoked.set(true));

            assertThat(invoked).as("origin %s", origin).isTrue();
        }
    }

    @Test
    void rejectsCrossSiteAndUntrustedOriginsBeforeCredentialUse() throws Exception {
        for (MockHttpServletRequest request : new MockHttpServletRequest[]{
                request("https://attacker.example", "same-origin", basic("vk-platform-bff", SECRET)),
                request("https://web.example.test", "cross-site", basic("vk-platform-bff", SECRET))
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (current, currentResponse) -> {
                throw new AssertionError("untrusted exchange reached the controller");
            });

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("UNTRUSTED_REQUEST_ORIGIN");
            assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        }
    }

    @Test
    void rejectsMissingOrInvalidBffCredentials() throws Exception {
        for (String authorization : new String[]{null, "Basic not-base64", basic("vk-platform-bff", "wrong")}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(
                    request("https://web.example.test", "same-origin", authorization),
                    response,
                    (current, currentResponse) -> {
                        throw new AssertionError("unauthenticated exchange reached the controller");
                    }
            );

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Basic realm=\"iam-bff\"");
            assertThat(response.getContentAsString()).contains("BFF_AUTHENTICATION_REQUIRED");
        }
    }

    private static MockHttpServletRequest request(
            String origin,
            String fetchSite,
            String authorization
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/authentication/grants/exchange");
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
