package com.vkplatform.iam.session.domain;

public enum SessionRevokeReason {
    LOGOUT("logout"),
    USER_REQUEST("user_request"),
    ALL_SESSIONS_REVOKED("all_sessions_revoked"),
    ACCOUNT_STATE_CHANGED("account_state_changed"),
    REFRESH_TOKEN_REUSED("refresh_token_reused"),
    EXPIRED("expired");

    private final String wireValue;

    SessionRevokeReason(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SessionRevokeReason fromStoredValue(String value) {
        for (SessionRevokeReason reason : values()) {
            if (reason.wireValue.equals(value)) {
                return reason;
            }
        }
        throw new IllegalStateException("unsupported stored session revoke reason");
    }
}
