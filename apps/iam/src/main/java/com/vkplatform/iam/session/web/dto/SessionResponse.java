package com.vkplatform.iam.session.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID id,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("expires_at") Instant expiresAt
) {
}
