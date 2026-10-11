package com.vkplatform.iam.identity.api.command;

import java.util.UUID;

public record AccountAdministrationCommand(
        UUID accountId,
        String actorId,
        String requestId
) {
}
