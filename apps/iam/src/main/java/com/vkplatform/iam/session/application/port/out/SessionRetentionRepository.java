package com.vkplatform.iam.session.application.port.out;

import java.time.Instant;

public interface SessionRetentionRepository {
    CleanupResult cleanup(
            Instant now,
            Instant sessionCutoff,
            Instant authorizationCutoff,
            Instant auditCutoff,
            int batchSize
    );

    record CleanupResult(
            int expiredSessions,
            int deletedAuthorizations,
            int deletedSessions,
            int deletedAuditEvents,
            int deletedSigningKeys
    ) {
        public int totalChanges() {
            return expiredSessions + deletedAuthorizations + deletedSessions
                    + deletedAuditEvents + deletedSigningKeys;
        }
    }
}
