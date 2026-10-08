package com.vkplatform.iam.verification.infrastructure.config;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationRepository;
import com.vkplatform.iam.verification.domain.VerificationChallenge;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationConfigurationTest {
    private final EmailVerificationConfiguration configuration = new EmailVerificationConfiguration();

    @Test
    void requiresEveryGmailCredentialWhenDeliveryIsEnabled() {
        assertThatThrownBy(() -> configuration.gmailVerificationEmailSender(
                properties("", "client", "secret", "refresh", secret(), secret(), Duration.ofHours(24), 100),
                tools.jackson.databind.json.JsonMapper.builder().build()
        )).hasMessageContaining("EMAIL_FROM_ADDRESS");
        assertThatThrownBy(() -> configuration.gmailVerificationEmailSender(
                properties("sender@example.com", "", "secret", "refresh", secret(), secret(), Duration.ofHours(24), 100),
                tools.jackson.databind.json.JsonMapper.builder().build()
        )).hasMessageContaining("GMAIL_CLIENT_ID");
        assertThatThrownBy(() -> configuration.gmailVerificationEmailSender(
                properties("sender@example.com", "client", "", "refresh", secret(), secret(), Duration.ofHours(24), 100),
                tools.jackson.databind.json.JsonMapper.builder().build()
        )).hasMessageContaining("GMAIL_CLIENT_SECRET");
        assertThatThrownBy(() -> configuration.gmailVerificationEmailSender(
                properties("sender@example.com", "client", "secret", "", secret(), secret(), Duration.ofHours(24), 100),
                tools.jackson.databind.json.JsonMapper.builder().build()
        )).hasMessageContaining("GMAIL_REFRESH_TOKEN");
    }

    @Test
    void requiresIndependentMinimumLengthHashingSecrets() {
        assertThatThrownBy(() -> configuration.verificationCodeHasher(properties(
                "sender@example.com",
                "client",
                "secret",
                "refresh",
                "short",
                secret(),
                Duration.ofHours(24),
                100
        ))).hasMessageContaining("EMAIL_OTP_PEPPER");
        assertThatThrownBy(() -> configuration.verificationCodeHasher(properties(
                "sender@example.com",
                "client",
                "secret",
                "refresh",
                secret(),
                "short",
                Duration.ofHours(24),
                100
        ))).hasMessageContaining("EMAIL_RATE_LIMIT_SECRET");
    }

    @Test
    void cleanupRetentionCannotUndercutTheGlobalThrottleWindow() {
        VerificationPolicy policy = VerificationPolicy.defaults();
        EmailVerificationRepository repository = new NoOpRepository();
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T03:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> configuration.emailVerificationCleanupService(
                repository,
                clock,
                properties(
                        "sender@example.com",
                        "client",
                        "secret",
                        "refresh",
                        secret(),
                        secret(),
                        Duration.ofHours(23),
                        100
                ),
                policy
        )).hasMessageContaining("EMAIL_VERIFICATION_RETENTION");
        assertThatThrownBy(() -> configuration.emailVerificationCleanupService(
                repository,
                clock,
                properties(
                        "sender@example.com",
                        "client",
                        "secret",
                        "refresh",
                        secret(),
                        secret(),
                        Duration.ofHours(24),
                        0
                ),
                policy
        )).hasMessageContaining("EMAIL_VERIFICATION_CLEANUP_BATCH_SIZE");
    }

    private static EmailVerificationProperties properties(
            String fromAddress,
            String clientId,
            String clientSecret,
            String refreshToken,
            String otpPepper,
            String rateLimitSecret,
            Duration cleanupRetention,
            int cleanupBatchSize
    ) {
        return new EmailVerificationProperties(
                "gmail",
                fromAddress,
                clientId,
                clientSecret,
                refreshToken,
                otpPepper,
                rateLimitSecret,
                cleanupRetention,
                cleanupBatchSize
        );
    }

    private static String secret() {
        return "a-secret-value-with-at-least-32-bytes";
    }

    private static final class NoOpRepository implements EmailVerificationRepository {
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
            return VerificationAttempt.rejected();
        }

        @Override
        public int deleteTerminalBefore(Instant cutoff, int batchSize) {
            return 0;
        }
    }
}
