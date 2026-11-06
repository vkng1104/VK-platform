package com.vkplatform.iam.verification.infrastructure.config;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationChallengeRepository;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationOperationGuardRepository;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
        EmailVerificationChallengeRepository challenges = mock(EmailVerificationChallengeRepository.class);
        EmailVerificationOperationGuardRepository operationGuards = mock(EmailVerificationOperationGuardRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-09T03:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> configuration.emailVerificationCleanupService(
                challenges,
                operationGuards,
                transactionManager,
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
                challenges,
                operationGuards,
                transactionManager,
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
}
