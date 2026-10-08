package com.vkplatform.iam.verification.infrastructure.config;

import tools.jackson.databind.ObjectMapper;
import com.vkplatform.iam.verification.application.EmailVerificationRepository;
import com.vkplatform.iam.verification.application.EmailVerificationService;
import com.vkplatform.iam.verification.application.VerificationEmailSender;
import com.vkplatform.iam.verification.domain.VerificationPolicy;
import com.vkplatform.iam.verification.infrastructure.mail.GmailVerificationEmailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class EmailVerificationConfiguration {
    @Bean
    Clock iamClock() {
        return Clock.systemUTC();
    }

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }

    @Bean
    @ConditionalOnProperty(name = "iam.email.provider", havingValue = "gmail")
    VerificationEmailSender gmailVerificationEmailSender(
            IamEmailProperties properties,
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
    EmailVerificationService emailVerificationService(
            EmailVerificationRepository repository,
            VerificationEmailSender sender,
            Clock clock,
            SecureRandom random,
            IamEmailProperties properties
    ) {
        requireSecret(properties.otpPepper(), "EMAIL_OTP_PEPPER");
        requireSecret(properties.rateLimitSecret(), "EMAIL_RATE_LIMIT_SECRET");
        return new EmailVerificationService(
                repository,
                sender,
                clock,
                random,
                properties.otpPepper().getBytes(StandardCharsets.UTF_8),
                properties.rateLimitSecret().getBytes(StandardCharsets.UTF_8),
                VerificationPolicy.defaults()
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
