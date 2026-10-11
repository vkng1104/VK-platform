package com.vkplatform.iam.identity.domain;

public final class IdentityFailure extends RuntimeException {
    public enum Reason {
        INVALID_GRANT,
        INVALID_AUDIENCE,
        ACCOUNT_NOT_FOUND,
        ACCOUNT_UNAVAILABLE,
        INVALID_ADMIN_CONTEXT
    }

    private final Reason reason;

    private IdentityFailure(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static IdentityFailure invalidGrant() {
        return new IdentityFailure(Reason.INVALID_GRANT, "authentication grant is invalid or expired");
    }

    public static IdentityFailure invalidAudience() {
        return new IdentityFailure(Reason.INVALID_AUDIENCE, "authentication audience is invalid");
    }

    public static IdentityFailure accountNotFound() {
        return new IdentityFailure(Reason.ACCOUNT_NOT_FOUND, "account was not found");
    }

    public static IdentityFailure accountUnavailable() {
        return new IdentityFailure(Reason.ACCOUNT_UNAVAILABLE, "account is unavailable");
    }

    public static IdentityFailure invalidAdminContext() {
        return new IdentityFailure(Reason.INVALID_ADMIN_CONTEXT, "account administration context is invalid");
    }

    public Reason reason() {
        return reason;
    }
}
