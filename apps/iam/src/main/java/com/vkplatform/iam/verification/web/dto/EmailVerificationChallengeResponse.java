package com.vkplatform.iam.verification.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record EmailVerificationChallengeResponse(
        UUID id,
        @JsonProperty("masked_email") String maskedEmail,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("resend_after") Instant resendAfter
) {
}
