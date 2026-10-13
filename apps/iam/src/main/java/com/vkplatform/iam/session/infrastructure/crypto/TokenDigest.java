package com.vkplatform.iam.session.infrastructure.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class TokenDigest {
    private static final String PLACEHOLDER_PREFIX = "sha256:";

    private TokenDigest() {
    }

    public static byte[] sha256(String value) {
        if (value == null || value.isBlank() || value.length() > 32768) {
            throw new IllegalArgumentException("token value is invalid");
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String placeholder(byte[] digest) {
        return PLACEHOLDER_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    public static byte[] digestOrRestore(String value) {
        if (value != null && value.startsWith(PLACEHOLDER_PREFIX)) {
            try {
                byte[] digest = Base64.getUrlDecoder().decode(value.substring(PLACEHOLDER_PREFIX.length()));
                if (digest.length != 32) {
                    throw new IllegalArgumentException("token digest placeholder is invalid");
                }
                return digest;
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("token digest placeholder is invalid", exception);
            }
        }
        return sha256(value);
    }

    public static boolean matches(byte[] left, byte[] right) {
        return left != null && right != null && MessageDigest.isEqual(left, right);
    }
}
