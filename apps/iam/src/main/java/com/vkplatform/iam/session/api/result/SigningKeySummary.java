package com.vkplatform.iam.session.api.result;

import java.time.Instant;

public record SigningKeySummary(
        String id,
        String algorithm,
        String status,
        Instant activatedAt,
        Instant publishUntil,
        Instant retiredAt
) {
}
