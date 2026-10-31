package com.vkplatform.iam.session.web.controller;

import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.identity.web.error.IdentityExceptionHandler;
import com.vkplatform.iam.identity.web.filter.AuthenticationBodyLimitFilter;
import com.vkplatform.iam.platform.web.GlobalExceptionHandler;
import com.vkplatform.iam.platform.web.RequestIdFilter;
import com.vkplatform.iam.session.api.AuthenticationSessionOperations;
import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.api.command.RevokeAllAuthenticationSessions;
import com.vkplatform.iam.session.api.command.RevokeAuthenticationSession;
import com.vkplatform.iam.session.api.result.AuthenticationSessionPage;
import com.vkplatform.iam.session.api.result.AuthenticationSessionSummary;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.web.mapper.AuthenticationSessionHttpMapper;
import com.vkplatform.iam.session.web.BrowserSessionCookie;
import com.vkplatform.iam.session.web.error.SessionExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationSessionControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("b0aa3948-ef87-4cb5-8913-2a85e8a32ac4");
    private static final UUID SESSION_ID = UUID.fromString("88167168-bd3f-4de1-b90e-dbd486fca568");
    private static final String GRANT = "A".repeat(43);

    private RecordingOperations operations;
    private BrowserSessionCookie browserCookie;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        operations = new RecordingOperations();
        browserCookie = mock(BrowserSessionCookie.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthenticationSessionController(
                        operations,
                        new AuthenticationSessionHttpMapper(),
                        new HttpSessionSecurityContextRepository(),
                        new ChangeSessionIdAuthenticationStrategy(),
                        browserCookie
                ))
                .setControllerAdvice(
                        new IdentityExceptionHandler(),
                        new SessionExceptionHandler(),
                        new GlobalExceptionHandler()
                )
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .addFilters(new RequestIdFilter(), new AuthenticationBodyLimitFilter())
                .build();
    }

    @Test
    void exchangesTheGrantAndPersistsTheBrowserAuthenticationContext() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/authentication/grants/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"grant":"%s","audience":"vk-platform-web"}
                                """.formatted(GRANT)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string(
                        "X-Request-ID",
                        org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{32}$")
                ))
                .andExpect(jsonPath("$.session.id").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.session.status").value("active"))
                .andExpect(jsonPath("$.session.created_at").value(NOW.toString()))
                .andExpect(jsonPath("$.session.last_refreshed_at").value(NOW.toString()))
                .andExpect(jsonPath("$.session.expires_at").value(NOW.plusSeconds(3600).toString()))
                .andExpect(jsonPath("$.session.client_id").value("vk-platform-bff"))
                .andReturn();

        assertThat(operations.exchange).isEqualTo(new ExchangeAuthenticationGrant(
                GRANT,
                "vk-platform-web",
                result.getResponse().getHeader("X-Request-ID")
        ));
        MockHttpSession browserSession = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(browserSession).isNotNull();
        SecurityContext context = (SecurityContext) browserSession.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication().getName()).isEqualTo(ACCOUNT_ID.toString());
        assertThat(context.getAuthentication().getDetails()).isEqualTo(Map.of(
                "session_id", SESSION_ID.toString(),
                "amr", "email_otp"
        ));
    }

    @Test
    void rejectsUnknownTrailingAndOversizedBodies() throws Exception {
        mockMvc.perform(post("/api/v1/authentication/grants/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"grant":"%s","audience":"vk-platform-web","unexpected":true}
                                """.formatted(GRANT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));

        mockMvc.perform(post("/api/v1/authentication/grants/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"grant":"%s","audience":"vk-platform-web"}{}
                                """.formatted(GRANT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));

        mockMvc.perform(post("/api/v1/authentication/grants/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"grant":"%s","audience":"%s"}
                                """.formatted(GRANT, "a".repeat(1_100))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    void returnsTheExistingSafeAuthenticationFailureWithoutCreatingABrowserSession() throws Exception {
        operations.failure = IdentityFailure.invalidGrant();

        MvcResult result = mockMvc.perform(post("/api/v1/authentication/grants/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"grant":"%s","audience":"vk-platform-web"}
                                """.formatted(GRANT)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    void readsAndPagesSessionsFromBearerClaims() throws Exception {
        mockMvc.perform(get("/api/v1/authentication/session").principal(principal()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.session.id").value(SESSION_ID.toString()));

        mockMvc.perform(get("/api/v1/authentication/sessions")
                        .queryParam("page", "1")
                        .queryParam("size", "10")
                        .principal(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].id").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.size").value(10))
                .andExpect(jsonPath("$.page.total_elements").value(11));

        assertThat(operations.currentAccountId).isEqualTo(ACCOUNT_ID);
        assertThat(operations.currentSessionId).isEqualTo(SESSION_ID);
        assertThat(operations.listPage).isEqualTo(1);
        assertThat(operations.listSize).isEqualTo(10);
    }

    @Test
    void revokesAnotherSessionWithoutClearingTheCurrentBrowser() throws Exception {
        UUID target = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/authentication/sessions/{session_id}", target)
                        .principal(principal()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(operations.revoke.targetSessionId()).isEqualTo(target);
        verifyNoInteractions(browserCookie);
    }

    @Test
    void currentRevocationAllRevocationAndLogoutClearBrowserState() throws Exception {
        for (String operation : List.of("current", "all", "logout")) {
            MockHttpSession browser = new MockHttpSession();
            var request = switch (operation) {
                case "current" -> delete(
                        "/api/v1/authentication/sessions/{session_id}",
                        SESSION_ID
                );
                case "all" -> delete("/api/v1/authentication/sessions");
                default -> post("/api/v1/authentication/logout");
            };

            mockMvc.perform(request.session(browser).principal(principal()))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Cache-Control", "no-store"));

            assertThat(browser.isInvalid()).isTrue();
        }

        verify(browserCookie, org.mockito.Mockito.times(3))
                .clear(org.mockito.ArgumentMatchers.any());
        assertThat(operations.revokeAll.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(operations.logout.targetSessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    void malformedBearerSessionClaimReturnsASafeUnauthorizedError() throws Exception {
        Jwt malformed = Jwt.withTokenValue("access-token")
                .header("alg", "RS256")
                .subject(ACCOUNT_ID.toString())
                .claim("sid", "not-a-uuid")
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(300))
                .build();

        mockMvc.perform(get("/api/v1/authentication/session")
                        .principal(new JwtAuthenticationToken(malformed)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    private static AuthenticationSession session() {
        return AuthenticationSession.create(
                SESSION_ID,
                ACCOUNT_ID,
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        );
    }

    private static AuthenticationSessionSummary summary() {
        return new AuthenticationSessionSummary(
                SESSION_ID,
                "vk-platform-bff",
                "active",
                NOW,
                NOW,
                NOW.plusSeconds(3600),
                null,
                null,
                null
        );
    }

    private static JwtAuthenticationToken principal() {
        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "RS256")
                .subject(ACCOUNT_ID.toString())
                .claim("sid", SESSION_ID.toString())
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt);
    }

    private static final class RecordingOperations implements AuthenticationSessionOperations {
        private ExchangeAuthenticationGrant exchange;
        private UUID currentAccountId;
        private UUID currentSessionId;
        private int listPage;
        private int listSize;
        private RevokeAuthenticationSession revoke;
        private RevokeAuthenticationSession logout;
        private RevokeAllAuthenticationSessions revokeAll;
        private RuntimeException failure;

        @Override
        public EstablishedAuthenticationSession exchange(ExchangeAuthenticationGrant command) {
            exchange = command;
            if (failure != null) {
                throw failure;
            }
            return new EstablishedAuthenticationSession(ACCOUNT_ID, session());
        }

        @Override
        public AuthenticationSessionSummary current(UUID accountId, UUID sessionId) {
            currentAccountId = accountId;
            currentSessionId = sessionId;
            return summary();
        }

        @Override
        public AuthenticationSessionPage sessions(
                UUID accountId,
                UUID currentSessionId,
                int page,
                int size
        ) {
            currentAccountId = accountId;
            this.currentSessionId = currentSessionId;
            listPage = page;
            listSize = size;
            return new AuthenticationSessionPage(List.of(summary()), page, size, 11, 2);
        }

        @Override
        public void revoke(RevokeAuthenticationSession command) {
            revoke = command;
        }

        @Override
        public void logout(RevokeAuthenticationSession command) {
            logout = command;
        }

        @Override
        public int revokeAll(RevokeAllAuthenticationSessions command) {
            revokeAll = command;
            return 1;
        }
    }
}
