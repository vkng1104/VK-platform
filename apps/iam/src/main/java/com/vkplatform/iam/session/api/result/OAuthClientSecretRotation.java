package com.vkplatform.iam.session.api.result;

import java.time.Instant;

public record OAuthClientSecretRotation(
        String clientId,
        Instant rotatedAt,
        Instant expiresAt
) {
}
