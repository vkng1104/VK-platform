package com.vkplatform.iam.session.web.controller;

import com.vkplatform.iam.platform.web.GlobalExceptionHandler;
import com.vkplatform.iam.platform.web.RequestIdFilter;
import com.vkplatform.iam.session.api.SessionOperations;
import com.vkplatform.iam.session.api.command.ExchangeSignInGrant;
import com.vkplatform.iam.session.api.command.RevokeAllSessions;
import com.vkplatform.iam.session.api.command.RevokeSession;
import com.vkplatform.iam.session.api.result.EstablishedSession;
import com.vkplatform.iam.session.api.result.SessionSummary;
import com.vkplatform.iam.session.web.error.SessionExceptionHandler;
import com.vkplatform.iam.session.web.filter.SessionBodyLimitFilter;
import com.vkplatform.iam.session.web.mapper.SessionHttpMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("79ee13f1-c682-437c-84a6-b4b5bec1408a");
    private static final UUID SESSION_ID = UUID.fromString("cf455bd8-d6a0-4a16-af02-bcabdc88df2f");

    private RecordingOperations operations;
    private SecurityContextRepository securityContexts;
    private SessionAuthenticationStrategy sessionAuthentication;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        operations = new RecordingOperations();
        securityContexts = mock(SecurityContextRepository.class);
        sessionAuthentication = mock(SessionAuthenticationStrategy.class);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new SessionController(
                        operations,
                        new SessionHttpMapper(),
                        securityContexts,
                        sessionAuthentication
                ))
                .setControllerAdvice(new SessionExceptionHandler(), new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .addFilters(new RequestIdFilter(), new SessionBodyLimitFilter())
                .build();
    }

    @Test
    void exchangesTheOneTimeGrantAndExplicitlyPersistsTheBrowserAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/authentication/grants/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"grant":"%s","audience":"vk-platform-web"}
                                """.formatted("A".repeat(43))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.session.id").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.session.client_id").value("vk-platform-bff"));

        assertThat(operations.exchange.grant()).isEqualTo("A".repeat(43));
        assertThat(operations.exchange.requestId()).matches("^[0-9a-f]{32}$");
        verify(sessionAuthentication).onAuthentication(any(), any(), any());
        ArgumentCaptor<SecurityContext> context = ArgumentCaptor.forClass(SecurityContext.class);
        verify(securityContexts).saveContext(context.capture(), any(), any());
        assertThat(context.getValue().getAuthentication().getName()).isEqualTo(ACCOUNT_ID.toString());
        assertThat(context.getValue().getAuthentication().getDetails())
                .isEqualTo(java.util.Map.of(
                        "session_id", SESSION_ID.toString(),
                        "amr", "email_otp"
                ));
    }

    @Test
    void resolvesTheCurrentSessionOnlyFromAllowListedJwtIdentityClaims() throws Exception {
        mockMvc.perform(get("/api/v1/authentication/session").principal(authentication()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.session.id").value(SESSION_ID.toString()));

        assertThat(operations.currentAccountId).isEqualTo(ACCOUNT_ID);
        assertThat(operations.currentSessionId).isEqualTo(SESSION_ID);
    }

    @Test
    void rejectsBearerTokensWithoutAValidSessionClaim() throws Exception {
        Jwt token = Jwt.withTokenValue("access-token")
                .header("alg", "RS256")
                .subject(ACCOUNT_ID.toString())
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(300))
                .build();

        mockMvc.perform(get("/api/v1/authentication/session")
                        .principal(new JwtAuthenticationToken(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    private static JwtAuthenticationToken authentication() {
        Jwt token = Jwt.withTokenValue("access-token")
                .header("alg", "RS256")
                .subject(ACCOUNT_ID.toString())
                .claim("sid", SESSION_ID.toString())
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(token);
    }

    private static SessionSummary summary() {
        return new SessionSummary(
                SESSION_ID,
                "vk-platform-bff",
                "active",
                NOW.minusSeconds(60),
                NOW.minusSeconds(30),
                NOW.plusSeconds(3600),
                null,
                null,
                null
        );
    }

    private static final class RecordingOperations implements SessionOperations {
        private ExchangeSignInGrant exchange;
        private UUID currentAccountId;
        private UUID currentSessionId;

        @Override
        public EstablishedSession exchange(ExchangeSignInGrant command) {
            exchange = command;
            return new EstablishedSession(ACCOUNT_ID, summary());
        }

        @Override
        public SessionSummary session(UUID accountId, UUID sessionId) {
            currentAccountId = accountId;
            currentSessionId = sessionId;
            return summary();
        }

        @Override
        public List<SessionSummary> sessions(UUID accountId) {
            return List.of(summary());
        }

        @Override
        public void revoke(RevokeSession command) {
        }

        @Override
        public void logout(RevokeSession command) {
        }

        @Override
        public int revokeAll(RevokeAllSessions command) {
            return 0;
        }
    }
}
