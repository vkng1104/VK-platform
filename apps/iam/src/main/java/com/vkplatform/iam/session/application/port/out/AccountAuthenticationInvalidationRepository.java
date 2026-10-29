package com.vkplatform.iam.session.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface AccountAuthenticationInvalidationRepository {
    void invalidateAccount(UUID accountId, Instant occurredAt);
}
