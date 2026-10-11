package com.vkplatform.iam.identity.infrastructure.crypto;

import com.vkplatform.iam.identity.application.port.out.GrantTokenCodec;
import com.vkplatform.iam.identity.domain.IdentityFailure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

public final class SecureGrantTokenCodec implements GrantTokenCodec {
    private static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final SecureRandom random;

    public SecureGrantTokenCodec(SecureRandom random) {
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public GeneratedGrant generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new GeneratedGrant(value, digestValue(value));
    }

    @Override
    public byte[] digest(String grant) {
        if (grant == null || !TOKEN_FORMAT.matcher(grant).matches()) {
            throw IdentityFailure.invalidGrant();
        }
        return digestValue(grant);
    }

    private static byte[] digestValue(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
