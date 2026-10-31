package com.vkplatform.iam.identity.api;

import com.vkplatform.iam.identity.api.command.RecordSecurityAuditEvent;

import java.time.Instant;

public interface SecurityAuditOperations {
    void record(RecordSecurityAuditEvent event);

    int deleteRetainedBefore(Instant cutoff, int batchSize);
}
