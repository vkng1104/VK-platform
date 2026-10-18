package com.vkplatform.iam.session.domain;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

public record SessionPolicy(
        URI issuer,
        String authenticationAudience,
        String bffClientId,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration absoluteSessionTtl
) {
    private static final Pattern VALUE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    public SessionPolicy {
        Objects.requireNonNull(issuer);
        Objects.requireNonNull(authenticationAudience);
        Objects.requireNonNull(bffClientId);
        Objects.requireNonNull(accessTokenTtl);
        Objects.requireNonNull(refreshTokenTtl);
        Objects.requireNonNull(absoluteSessionTtl);
        if (!issuer.isAbsolute() || issuer.getFragment() != null || issuer.getQuery() != null) {
            throw new IllegalArgumentException("token issuer must be an absolute URI");
        }
        if (!VALUE.matcher(authenticationAudience).matches() || !VALUE.matcher(bffClientId).matches()) {
            throw new IllegalArgumentException("session audience or client is invalid");
        }
        if (accessTokenTtl.isZero() || accessTokenTtl.isNegative()
                || accessTokenTtl.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("access-token TTL must be greater than zero and at most 15 minutes");
        }
        if (refreshTokenTtl.isZero() || refreshTokenTtl.isNegative()
                || absoluteSessionTtl.isZero() || absoluteSessionTtl.isNegative()
                || refreshTokenTtl.compareTo(absoluteSessionTtl) > 0) {
            throw new IllegalArgumentException("refresh and session TTLs are invalid");
        }
    }
}
