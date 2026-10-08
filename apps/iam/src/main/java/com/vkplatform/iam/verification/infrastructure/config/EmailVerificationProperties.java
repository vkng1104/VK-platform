package com.vkplatform.iam.verification.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("iam.email")
public record EmailVerificationProperties(
        String provider,
        String fromAddress,
        String gmailClientId,
        String gmailClientSecret,
        String gmailRefreshToken,
        String otpPepper,
        String rateLimitSecret
) {
}
