package com.vkplatform.iam.identity.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record AuthenticationChallengeResponse(
        UUID id,
        @JsonProperty("masked_email") String maskedEmail,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("resend_after") Instant resendAfter
) {
}
