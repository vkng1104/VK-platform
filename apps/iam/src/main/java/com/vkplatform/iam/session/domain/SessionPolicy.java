package com.vkplatform.iam.session.domain;

import java.time.Duration;

public record SessionPolicy(
        String bffClientId,
        Duration absoluteTtl
) {
    public SessionPolicy {
        if (bffClientId == null || bffClientId.isBlank() || bffClientId.length() > 128
                || !bffClientId.equals(bffClientId.strip())) {
            throw new IllegalStateException("IAM_BFF_CLIENT_ID is invalid");
        }
        if (absoluteTtl == null || absoluteTtl.isZero() || absoluteTtl.isNegative()) {
            throw new IllegalStateException("IAM_ABSOLUTE_SESSION_TTL must be greater than zero");
        }
    }
}
