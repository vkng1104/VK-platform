package com.vkplatform.iam.session.api.command;

import java.time.Instant;

public record RotateOAuthClientSecret(
        String clientId,
        String newSecret,
        Instant expiresAt
) {
}
