package com.vkplatform.iam.session.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID id,
        @JsonProperty("client_id") String clientId,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("last_seen_at") Instant lastSeenAt,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("revoked_at") Instant revokedAt,
        @JsonProperty("compromised_at") Instant compromisedAt,
        @JsonProperty("revoke_reason") String revokeReason
) {
}
