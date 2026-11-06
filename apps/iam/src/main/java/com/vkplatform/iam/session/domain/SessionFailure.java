package com.vkplatform.iam.session.domain;

public final class SessionFailure extends RuntimeException {
    public enum Reason {
        INVALID_REQUEST,
        SESSION_NOT_FOUND,
        SESSION_UNAVAILABLE
    }

    private final Reason reason;

    private SessionFailure(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static SessionFailure invalidRequest() {
        return new SessionFailure(Reason.INVALID_REQUEST, "session request is invalid");
    }

    public static SessionFailure sessionNotFound() {
        return new SessionFailure(Reason.SESSION_NOT_FOUND, "session was not found");
    }

    public static SessionFailure sessionUnavailable() {
        return new SessionFailure(Reason.SESSION_UNAVAILABLE, "session is unavailable");
    }

    public Reason reason() {
        return reason;
    }
}
