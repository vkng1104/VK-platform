package com.vkplatform.iam.session.api.command;

import java.util.UUID;

public record RevokeAllAuthenticationSessions(
        UUID accountId,
        UUID currentSessionId,
        String requestId
) {
}
