package com.vkplatform.iam.identity.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record IdentityPolicy(String audience, Duration grantTtl) {
    private static final Pattern AUDIENCE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public IdentityPolicy {
        Objects.requireNonNull(audience);
        Objects.requireNonNull(grantTtl);
        if (!AUDIENCE.matcher(audience).matches()) {
            throw new IllegalArgumentException("authentication audience is invalid");
        }
        if (grantTtl.isZero() || grantTtl.isNegative() || grantTtl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("authentication grant TTL must be between zero and 15 minutes");
        }
    }
}
