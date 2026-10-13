package com.vkplatform.iam.session.api.command;

import java.util.UUID;

public record RevokeAllSessions(
        UUID accountId,
        UUID exceptSessionId,
        String requestId
) {
}
