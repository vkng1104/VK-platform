package com.vkplatform.iam.verification.web.controller;

import com.vkplatform.iam.platform.web.GlobalExceptionHandler;
import com.vkplatform.iam.platform.web.RequestIdFilter;
import com.vkplatform.iam.verification.api.EmailVerificationOperations;
import com.vkplatform.iam.verification.api.command.StartEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import com.vkplatform.iam.verification.domain.VerificationFailure;
import com.vkplatform.iam.verification.web.error.EmailVerificationExceptionHandler;
import com.vkplatform.iam.verification.web.filter.EmailVerificationBodyLimitFilter;
import com.vkplatform.iam.verification.web.mapper.EmailVerificationHttpMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmailVerificationControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");
    private RecordingOperations operations;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        operations = new RecordingOperations();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        mockMvc = MockMvcBuilders.standaloneSetup(new EmailVerificationController(
                        operations,
                        new EmailVerificationHttpMapper()
                ))
                .setControllerAdvice(
                        new EmailVerificationExceptionHandler(clock),
                        new GlobalExceptionHandler()
                )
                .addFilters(new RequestIdFilter(), new EmailVerificationBodyLimitFilter())
                .build();
    }

    @Test
    void startsAChallengeWithTheStableWireContract() throws Exception {
        mockMvc.perform(post("/api/v1/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"reader@example.com","purpose":"restricted_resource_access"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Request-ID", org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.verification.masked_email").value("r****r@example.com"))
                .andExpect(jsonPath("$.verification.expires_at").value("2026-10-08T02:05:00Z"))
                .andExpect(jsonPath("$.verification.resend_after").value("2026-10-08T02:01:00Z"));

        assertThat(operations.start.email()).isEqualTo("reader@example.com");
        assertThat(operations.start.purpose()).isEqualTo("restricted_resource_access");
        assertThat(operations.start.requesterAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void mapsDomainFailuresWithoutLeakingInternalDetails() throws Exception {
        operations.startFailure = VerificationFailure.invalidEmail();

        mockMvc.perform(post("/api/v1/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"invalid","purpose":"restricted_resource_access"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_EMAIL"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.fields.email[0]").value("Enter a valid email address."))
                .andExpect(jsonPath("$.request_id", org.hamcrest.Matchers.matchesPattern("^[0-9a-f]{32}$")));
    }

    @Test
    void rejectsMalformedAndOversizedBodies() throws Exception {
        mockMvc.perform(post("/api/v1/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));

        String oversized = "{\"email\":\"" + "a".repeat(4_200)
                + "@example.com\",\"purpose\":\"restricted_resource_access\"}";
        mockMvc.perform(post("/api/v1/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversized))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    void verifiesAChallengeAndUsesTheGenericRejectionResponse() throws Exception {
        String id = "123e4567-e89b-42d3-a456-426614174000";
        mockMvc.perform(post("/api/v1/email-verifications/{id}/verify", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification.id").value(id))
                .andExpect(jsonPath("$.verification.purpose").value("restricted_resource_access"))
                .andExpect(jsonPath("$.verification.verified_at").value("2026-10-08T02:00:00Z"));

        assertThat(operations.verify.verificationId()).isEqualTo(id);
        assertThat(operations.verify.code()).isEqualTo("123456");

        operations.verifyFailure = VerificationFailure.invalidOrExpiredCode();
        mockMvc.perform(post("/api/v1/email-verifications/{id}/verify", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"999999\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_CODE"));
    }

    private static final class RecordingOperations implements EmailVerificationOperations {
        private StartEmailVerification start;
        private VerifyEmailCode verify;
        private RuntimeException startFailure;
        private RuntimeException verifyFailure;

        @Override
        public VerificationChallengeStarted start(StartEmailVerification command) {
            start = command;
            if (startFailure != null) {
                throw startFailure;
            }
            return new VerificationChallengeStarted(
                    UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
                    "r****r@example.com",
                    NOW.plusSeconds(300),
                    NOW.plusSeconds(60)
            );
        }

        @Override
        public EmailVerified verify(VerifyEmailCode command) {
            verify = command;
            if (verifyFailure != null) {
                throw verifyFailure;
            }
            return new EmailVerified(
                    UUID.fromString(command.verificationId()),
                    "restricted_resource_access",
                    NOW
            );
        }
    }
}
