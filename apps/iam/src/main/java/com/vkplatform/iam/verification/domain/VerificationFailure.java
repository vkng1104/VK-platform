package com.vkplatform.iam.verification.domain;

import java.time.Instant;

public final class VerificationFailure extends RuntimeException {
    public enum Reason {
        INVALID_EMAIL,
        INVALID_PURPOSE,
        INVALID_ID,
        INVALID_CODE_FORMAT,
        INVALID_OR_EXPIRED_CODE,
        RATE_LIMITED,
        DELIVERY_UNAVAILABLE
    }

    private final Reason reason;
    private final Instant retryAt;

    private VerificationFailure(Reason reason, String message, Instant retryAt, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryAt = retryAt;
    }

    public static VerificationFailure invalidEmail() {
        return new VerificationFailure(Reason.INVALID_EMAIL, "email address is invalid", null, null);
    }

    public static VerificationFailure invalidPurpose() {
        return new VerificationFailure(Reason.INVALID_PURPOSE, "verification purpose is invalid", null, null);
    }

    public static VerificationFailure invalidId() {
        return new VerificationFailure(Reason.INVALID_ID, "verification ID is invalid", null, null);
    }

    public static VerificationFailure invalidCodeFormat() {
        return new VerificationFailure(Reason.INVALID_CODE_FORMAT, "verification code format is invalid", null, null);
    }

    public static VerificationFailure invalidOrExpiredCode() {
        return new VerificationFailure(
                Reason.INVALID_OR_EXPIRED_CODE,
                "verification code is invalid or expired",
                null,
                null
        );
    }

    public static VerificationFailure rateLimited(Instant retryAt) {
        return new VerificationFailure(Reason.RATE_LIMITED, "email verification rate limited", retryAt, null);
    }

    public static VerificationFailure deliveryUnavailable(Throwable cause) {
        return new VerificationFailure(
                Reason.DELIVERY_UNAVAILABLE,
                "email verification delivery unavailable",
                null,
                cause
        );
    }

    public Reason reason() {
        return reason;
    }

    public Instant retryAt() {
        return retryAt;
    }
}
