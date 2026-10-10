package com.vkplatform.iam.verification.infrastructure.config;

import com.vkplatform.iam.verification.api.EmailVerificationOperations;
import com.vkplatform.iam.verification.application.port.out.EmailVerificationRepository;
import com.vkplatform.iam.verification.application.port.out.VerificationCodeGenerator;
import com.vkplatform.iam.verification.application.port.out.VerificationCodeHasher;
import com.vkplatform.iam.verification.application.port.out.VerificationEmailSender;
import com.vkplatform.iam.verification.application.service.EmailVerificationCleanupService;
import com.vkplatform.iam.verification.application.service.EmailVerificationService;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.infrastructure.crypto.HmacVerificationCodeHasher;
import com.vkplatform.iam.verification.infrastructure.crypto.SecureVerificationCodeGenerator;
import com.vkplatform.iam.verification.infrastructure.mail.GmailVerificationEmailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class EmailVerificationConfiguration {
    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    @ConditionalOnProperty(name = "iam.email.provider", havingValue = "gmail")
    VerificationEmailSender gmailVerificationEmailSender(
            EmailVerificationProperties properties,
            ObjectMapper objectMapper
    ) {
        requireConfigured(properties.fromAddress(), "EMAIL_FROM_ADDRESS");
        requireConfigured(properties.gmailClientId(), "GMAIL_CLIENT_ID");
        requireConfigured(properties.gmailClientSecret(), "GMAIL_CLIENT_SECRET");
        requireConfigured(properties.gmailRefreshToken(), "GMAIL_REFRESH_TOKEN");
        return new GmailVerificationEmailSender(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper,
                properties.fromAddress().strip(),
                properties.gmailClientId().strip(),
                properties.gmailClientSecret().strip(),
                properties.gmailRefreshToken().strip()
        );
    }

    @Bean
    @ConditionalOnProperty(name = "iam.email.provider", havingValue = "gmail")
    VerificationCodeGenerator verificationCodeGenerator(SecureRandom random) {
        return new SecureVerificationCodeGenerator(random);
    }

    @Bean
    @ConditionalOnProperty(name = "iam.email.provider", havingValue = "gmail")
    VerificationCodeHasher verificationCodeHasher(EmailVerificationProperties properties) {
        requireSecret(properties.otpPepper(), "EMAIL_OTP_PEPPER");
        requireSecret(properties.rateLimitSecret(), "EMAIL_RATE_LIMIT_SECRET");
        return new HmacVerificationCodeHasher(
                properties.otpPepper().getBytes(StandardCharsets.UTF_8),
                properties.rateLimitSecret().getBytes(StandardCharsets.UTF_8)
        );
    }

    @Bean
    VerificationPolicy verificationPolicy() {
        return VerificationPolicy.defaults();
    }

    @Bean
    EmailVerificationCleanupService emailVerificationCleanupService(
            EmailVerificationRepository repository,
            Clock clock,
            EmailVerificationProperties properties,
            VerificationPolicy policy
    ) {
        Duration retention = properties.cleanupRetention();
        if (retention == null || retention.compareTo(policy.globalWindow()) < 0) {
            throw new IllegalStateException(
                    "EMAIL_VERIFICATION_RETENTION must be at least " + policy.globalWindow()
            );
        }
        if (properties.cleanupBatchSize() <= 0) {
            throw new IllegalStateException("EMAIL_VERIFICATION_CLEANUP_BATCH_SIZE must be positive");
        }
        return new EmailVerificationCleanupService(
                repository,
                clock,
                retention,
                properties.cleanupBatchSize()
        );
    }

    @Bean
    @ConditionalOnProperty(name = "iam.email.provider", havingValue = "gmail")
    EmailVerificationOperations emailVerificationOperations(
            EmailVerificationRepository repository,
            VerificationEmailSender sender,
            Clock clock,
            VerificationCodeGenerator codeGenerator,
            VerificationCodeHasher hasher,
            VerificationPolicy policy
    ) {
        return new EmailVerificationService(
                repository,
                sender,
                clock,
                codeGenerator,
                hasher,
                policy
        );
    }

    private static void requireConfigured(String value, String environmentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentName + " is required when EMAIL_PROVIDER=gmail");
        }
    }

    private static void requireSecret(String value, String environmentName) {
        requireConfigured(value, environmentName);
        if (value.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(environmentName + " must contain at least 32 bytes");
        }
    }
}
