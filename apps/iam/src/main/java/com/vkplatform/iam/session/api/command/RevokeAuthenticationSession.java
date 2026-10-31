package com.vkplatform.iam.session.api.command;

import java.util.UUID;

public record RevokeAuthenticationSession(
        UUID accountId,
        UUID currentSessionId,
        UUID targetSessionId,
        String requestId
) {
}
