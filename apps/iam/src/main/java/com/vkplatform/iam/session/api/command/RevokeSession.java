package com.vkplatform.iam.session.api.command;

import java.util.UUID;

public record RevokeSession(
        UUID accountId,
        UUID sessionId,
        String requestId
) {
}
