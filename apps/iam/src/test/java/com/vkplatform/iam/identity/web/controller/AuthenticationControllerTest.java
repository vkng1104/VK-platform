package com.vkplatform.iam.identity.web.controller;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.command.AccountAdministrationCommand;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.command.StartPasswordlessSignIn;
import com.vkplatform.iam.identity.api.command.VerifyPasswordlessSignIn;
import com.vkplatform.iam.identity.api.result.AccountSummary;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.identity.api.result.AuthenticationChallengeStarted;
import com.vkplatform.iam.identity.api.result.AuthenticationGrantIssued;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.identity.web.error.IdentityExceptionHandler;
import com.vkplatform.iam.identity.web.mapper.IdentityHttpMapper;
import com.vkplatform.iam.platform.web.GlobalExceptionHandler;
import com.vkplatform.iam.platform.web.RequestIdFilter;
import com.vkplatform.iam.platform.web.RequestBodyLimitFilter;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.web.error.EmailVerificationExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-11T03:00:00Z");
    private static final UUID CHALLENGE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
    private static final UUID ACCOUNT_ID = UUID.fromString("3d64af52-d808-4cf0-a864-c7ed43f1f802");
    private static final String GRANT = "A".repeat(43);

    private RecordingOperations operations;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        operations = new RecordingOperations();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        JsonMapper objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthenticationController(
                        operations,
                        new IdentityHttpMapper()
                ))
                .setControllerAdvice(
                        new IdentityExceptionHandler(),
                        new EmailVerificationExceptionHandler(clock),
                        new GlobalExceptionHandler()
                )
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .addFilters(new RequestIdFilter(), new RequestBodyLimitFilter(objectMapper))
                .build();
    }

    @Test
    void startsSignInWithTheStableWireContract() throws Exception {
        mockMvc.perform(post("/api/v1/authentication/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reader@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(content().json(contractFixture("authentication-v1-start-response.json")));

        assertThat(operations.start.email()).isEqualTo("reader@example.com");
        assertThat(operations.start.requesterAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void verifiesSignInAndReturnsTheRawGrantOnlyInTheResponse() throws Exception {
        mockMvc.perform(post("/api/v1/authentication/challenges/{id}/verify", CHALLENGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json(contractFixture("authentication-v1-grant-response.json")))
                .andExpect(jsonPath("$.grant.value").value(GRANT));

        assertThat(operations.verify.challengeId()).isEqualTo(CHALLENGE_ID.toString());
        assertThat(operations.verify.code()).isEqualTo("123456");
        assertThat(operations.verify.requestId()).matches("^[0-9a-f]{32}$");
    }

    @Test
    void rejectsUnknownTrailingAndOversizedBodies() throws Exception {
        mockMvc.perform(post("/api/v1/authentication/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reader@example.com\",\"unexpected\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));

        mockMvc.perform(post("/api/v1/authentication/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"reader@example.com\"}{\"email\":\"other@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));

        String oversized = "{\"email\":\"" + "a".repeat(4_200) + "@example.com\"}";
        mockMvc.perform(post("/api/v1/authentication/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("REQUEST_BODY_TOO_LARGE"));
    }

    @Test
    void preservesGenericVerificationAndAccountFailures() throws Exception {
        operations.startFailure = VerificationFailure.invalidEmail();
        mockMvc.perform(post("/api/v1/authentication/challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EMAIL"));

        operations.startFailure = null;
        operations.verifyFailure = VerificationFailure.invalidOrExpiredCode();
        mockMvc.perform(post("/api/v1/authentication/challenges/{id}/verify", CHALLENGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"999999\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_CODE"));

        operations.verifyFailure = IdentityFailure.accountUnavailable();
        mockMvc.perform(post("/api/v1/authentication/challenges/{id}/verify", CHALLENGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(GRANT))));
    }

    private static String contractFixture(String filename) throws Exception {
        return new ClassPathResource("contracts/" + filename)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static final class RecordingOperations implements PasswordlessIdentityOperations {
        private StartPasswordlessSignIn start;
        private VerifyPasswordlessSignIn verify;
        private RuntimeException startFailure;
        private RuntimeException verifyFailure;

        @Override
        public AuthenticationChallengeStarted startSignIn(StartPasswordlessSignIn command) {
            start = command;
            if (startFailure != null) {
                throw startFailure;
            }
            return new AuthenticationChallengeStarted(
                    CHALLENGE_ID,
                    "r****r@example.com",
                    NOW.plusSeconds(300),
                    NOW.plusSeconds(60)
            );
        }

        @Override
        public AuthenticationGrantIssued verifySignIn(VerifyPasswordlessSignIn command) {
            verify = command;
            if (verifyFailure != null) {
                throw verifyFailure;
            }
            return new AuthenticationGrantIssued(
                    ACCOUNT_ID,
                    GRANT,
                    "vk-platform-web",
                    NOW.plusSeconds(300)
            );
        }

        @Override
        public AuthenticatedAccount consumeSignInGrant(ConsumeSignInGrant command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthenticatedAccount activeAccount(UUID accountId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountSummary account(UUID accountId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountSummary suspendAccount(AccountAdministrationCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountSummary reactivateAccount(AccountAdministrationCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AccountSummary deleteAccount(AccountAdministrationCommand command) {
            throw new UnsupportedOperationException();
        }
    }
}
