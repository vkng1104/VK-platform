package com.vkplatform.iam.identity.api;

import com.vkplatform.iam.identity.api.event.SecurityAuditEvent;

public interface SecurityAuditOperations {
    void append(SecurityAuditEvent event);
}
