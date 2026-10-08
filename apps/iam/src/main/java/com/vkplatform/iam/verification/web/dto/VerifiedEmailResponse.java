package com.vkplatform.iam.verification.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record VerifiedEmailResponse(
        UUID id,
        String purpose,
        @JsonProperty("verified_at") Instant verifiedAt
) {
}
