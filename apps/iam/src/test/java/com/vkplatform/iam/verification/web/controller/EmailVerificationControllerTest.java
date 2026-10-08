package com.vkplatform.iam.verification.web.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

class EmailVerificationControllerTest {
    private static final Instant NOW = Instant.parse("2026-10-08T02:00:00Z");
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
        mockMvc = MockMvcBuilders.standaloneSetup(new EmailVerificationController(
                        operations,
                        new EmailVerificationHttpMapper()
                ))
                .setControllerAdvice(
                        new EmailVerificationExceptionHandler(clock),
                        new GlobalExceptionHandler()
                )
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
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
                .andExpect(content().json(contractFixture("email-verification-v1-start-response.json")))
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
    void rejectsUnknownFieldsAndTrailingJsonValues() throws Exception {
        mockMvc.perform(post("/api/v1/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"reader@example.com",
                                  "purpose":"restricted_resource_access",
                                  "unexpected":true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));

        mockMvc.perform(post("/api/v1/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"reader@example.com","purpose":"restricted_resource_access"}
                                {"email":"second@example.com","purpose":"restricted_resource_access"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    void mapsRateLimitsWithRetryAfter() throws Exception {
        operations.startFailure = VerificationFailure.rateLimited(NOW.plusSeconds(45));

        mockMvc.perform(post("/api/v1/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"reader@example.com","purpose":"restricted_resource_access"}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "45"))
                .andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void preservesEveryValidationErrorCodeFromTheV1Contract() throws Exception {
        operations.startFailure = VerificationFailure.invalidPurpose();
        mockMvc.perform(post("/api/v1/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"reader@example.com","purpose":"unsupported"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_PURPOSE"));

        operations.verifyFailure = VerificationFailure.invalidId();
        mockMvc.perform(post("/api/v1/email-verifications/{id}/verify", "invalid-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_ID"));

        operations.verifyFailure = VerificationFailure.invalidCodeFormat();
        mockMvc.perform(post(
                        "/api/v1/email-verifications/{id}/verify",
                        "123e4567-e89b-42d3-a456-426614174000"
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CODE_FORMAT"));
    }

    @Test
    void redactsProviderFailuresFromResponsesAndLogs() throws Exception {
        String providerSecret = "provider-secret-that-must-not-leak";
        String rawEmail = "private-reader@example.com";
        String digest = "f00dcafe0123456789abcdef0123456789abcdef0123456789abcdef01234567";
        operations.startFailure = VerificationFailure.deliveryUnavailable(
                new IllegalStateException(providerSecret + " 123456 " + digest)
        );
        Logger logger = (Logger) LoggerFactory.getLogger(EmailVerificationExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            String response = mockMvc.perform(post("/api/v1/email-verifications")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"%s","purpose":"restricted_resource_access"}
                                    """.formatted(rawEmail)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("EMAIL_DELIVERY_UNAVAILABLE"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            assertThat(response).doesNotContain(providerSecret, rawEmail, "123456", digest);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message)
                            .doesNotContain(providerSecret, rawEmail, "123456", digest));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void verifiesAChallengeAndUsesTheGenericRejectionResponse() throws Exception {
        String id = "123e4567-e89b-42d3-a456-426614174000";
        mockMvc.perform(post("/api/v1/email-verifications/{id}/verify", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json(contractFixture("email-verification-v1-verify-response.json")))
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

    private static String contractFixture(String filename) throws Exception {
        return new ClassPathResource("contracts/" + filename)
                .getContentAsString(StandardCharsets.UTF_8);
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
