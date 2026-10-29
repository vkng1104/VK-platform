package com.vkplatform.iam.identity.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountAuthenticationInvalidated(
        UUID accountId,
        Instant occurredAt
) {
    public AccountAuthenticationInvalidated {
        Objects.requireNonNull(accountId);
        Objects.requireNonNull(occurredAt);
    }
}
