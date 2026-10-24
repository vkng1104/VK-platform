package com.vkplatform.iam.session.web.controller;

import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.identity.web.error.IdentityExceptionHandler;
import com.vkplatform.iam.identity.web.filter.AuthenticationBodyLimitFilter;
import com.vkplatform.iam.platform.web.GlobalExceptionHandler;
import com.vkplatform.iam.platform.web.RequestIdFilter;
import com.vkplatform.iam.session.api.AuthenticationSessionOperations;
import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.web.mapper.AuthenticationSessionHttpMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationSessionControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("b0aa3948-ef87-4cb5-8913-2a85e8a32ac4");
    private static final UUID SESSION_ID = UUID.fromString("88167168-bd3f-4de1-b90e-dbd486fca568");
    private static final String GRANT = "A".repeat(43);

    private RecordingOperations operations;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        operations = new RecordingOperations();
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthenticationSessionController(
                        operations,
                        new AuthenticationSessionHttpMapper(),
                        new HttpSessionSecurityContextRepository(),
                        new ChangeSessionIdAuthenticationStrategy()
                ))
                .setControllerAdvice(new IdentityExceptionHandler(), new GlobalExceptionHandler())
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
                .andExpect(jsonPath("$.session.expires_at").value(NOW.plusSeconds(3600).toString()))
                .andExpect(jsonPath("$.session.client_id").doesNotExist())
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

    private static AuthenticationSession session() {
        return AuthenticationSession.create(
                SESSION_ID,
                ACCOUNT_ID,
                "vk-platform-bff",
                NOW,
                NOW.plusSeconds(3600)
        );
    }

    private static final class RecordingOperations implements AuthenticationSessionOperations {
        private ExchangeAuthenticationGrant exchange;
        private RuntimeException failure;

        @Override
        public EstablishedAuthenticationSession exchange(ExchangeAuthenticationGrant command) {
            exchange = command;
            if (failure != null) {
                throw failure;
            }
            return new EstablishedAuthenticationSession(ACCOUNT_ID, session());
        }
    }
}
