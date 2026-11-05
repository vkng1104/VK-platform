package com.vkplatform.iam.identity.application.port.out;

import com.vkplatform.iam.identity.domain.SecurityEvent;

public interface SecurityEventRepository {
    void append(SecurityEvent event);
}
