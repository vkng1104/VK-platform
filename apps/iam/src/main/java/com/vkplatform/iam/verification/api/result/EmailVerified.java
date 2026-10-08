package com.vkplatform.iam.verification.api.result;

import java.time.Instant;
import java.util.UUID;

public record EmailVerified(UUID id, String purpose, Instant verifiedAt) {
}
