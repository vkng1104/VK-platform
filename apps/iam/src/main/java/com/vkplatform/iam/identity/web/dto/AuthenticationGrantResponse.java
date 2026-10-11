package com.vkplatform.iam.identity.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record AuthenticationGrantResponse(
        @JsonProperty("account_id") UUID accountId,
        String value,
        String audience,
        @JsonProperty("expires_at") Instant expiresAt
) {
}
