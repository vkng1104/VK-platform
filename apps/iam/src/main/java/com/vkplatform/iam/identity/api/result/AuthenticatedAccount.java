package com.vkplatform.iam.identity.api.result;

import java.time.Instant;
import java.util.UUID;

public record AuthenticatedAccount(
        UUID accountId,
        Instant tokenNotBefore
) {
}
