package com.vkplatform.iam.session.domain;

public class SessionFailure extends RuntimeException {
    public enum Reason {
        INVALID_REQUEST,
        INVALID_REFRESH_TOKEN,
        REFRESH_TOKEN_REUSED,
        SESSION_NOT_FOUND,
        SESSION_UNAVAILABLE,
        TOKEN_ISSUANCE_UNAVAILABLE
    }

    private final Reason reason;

    protected SessionFailure(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static SessionFailure invalidRequest() {
        return new SessionFailure(Reason.INVALID_REQUEST, "session request is invalid");
    }

    public static SessionFailure invalidRefreshToken() {
        return new SessionFailure(Reason.INVALID_REFRESH_TOKEN, "refresh token is invalid or expired");
    }

    public static SessionFailure refreshTokenReused() {
        return new RefreshTokenReuseFailure();
    }

    public static SessionFailure sessionNotFound() {
        return new SessionFailure(Reason.SESSION_NOT_FOUND, "session was not found");
    }

    public static SessionFailure sessionUnavailable() {
        return new SessionFailure(Reason.SESSION_UNAVAILABLE, "session is unavailable");
    }

    public static SessionFailure tokenIssuanceUnavailable() {
        return new SessionFailure(Reason.TOKEN_ISSUANCE_UNAVAILABLE, "token issuance is unavailable");
    }

    public Reason reason() {
        return reason;
    }

    public static final class RefreshTokenReuseFailure extends SessionFailure {
        private RefreshTokenReuseFailure() {
            super(Reason.REFRESH_TOKEN_REUSED, "refresh token reuse was detected");
        }
    }
}
