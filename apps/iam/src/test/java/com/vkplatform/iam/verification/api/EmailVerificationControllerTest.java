package com.vkplatform.iam.verification.api;

import com.vkplatform.iam.platform.web.EmailVerificationBodyLimitFilter;
import com.vkplatform.iam.platform.web.RequestIdFilter;
import com.vkplatform.iam.verification.application.EmailVerificationRepository;
import com.vkplatform.iam.verification.application.EmailVerificationService;
import com.vkplatform.iam.verification.application.VerificationEmailSender;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.SecureRandom;
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
    private RecordingRepository repository;
    private RecordingSender sender;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = new RecordingRepository();
        sender = new RecordingSender();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        EmailVerificationService service = new EmailVerificationService(
                repository,
                sender,
                clock,
                new FixedSecureRandom(),
                "otp-pepper-must-be-at-least-32-bytes".getBytes(),
                "fingerprint-key-must-be-32-bytes!!".getBytes(),
                VerificationPolicy.defaults()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new EmailVerificationController(service))
                .setControllerAdvice(new EmailVerificationExceptionHandler(clock))
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

        assertThat(sender.code).isEqualTo("123456");
    }

    @Test
    void returnsFieldErrorsWithoutLeakingInternalDetails() throws Exception {
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
        repository.attempt = EmailVerificationRepository.VerificationAttempt.success();
        mockMvc.perform(post("/api/v1/email-verifications/{id}/verify", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification.id").value(id))
                .andExpect(jsonPath("$.verification.purpose").value("restricted_resource_access"))
                .andExpect(jsonPath("$.verification.verified_at").value("2026-10-08T02:00:00Z"));

        repository.attempt = EmailVerificationRepository.VerificationAttempt.rejected();
        mockMvc.perform(post("/api/v1/email-verifications/{id}/verify", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"999999\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_OR_EXPIRED_CODE"));
    }

    private static final class FixedSecureRandom extends SecureRandom {
        @Override
        public int nextInt(int bound) {
            return 123456;
        }
    }

    private static final class RecordingSender implements VerificationEmailSender {
        private String code;

        @Override
        public void send(String recipient, String code, Instant expiresAt) {
            this.code = code;
        }
    }

    private static final class RecordingRepository implements EmailVerificationRepository {
        private VerificationAttempt attempt = VerificationAttempt.rejected();

        @Override
        public void createPending(VerificationChallenge challenge, VerificationPolicy policy) {
        }

        @Override
        public void markSent(UUID id, Instant sentAt) {
        }

        @Override
        public void markFailed(UUID id) {
        }

        @Override
        public VerificationAttempt verify(UUID id, byte[] candidateDigest, Instant now) {
            return attempt;
        }
    }
}
