package com.vkplatform.iam.session.domain;

public enum OAuthAuthorizationInvalidationReason {
    AUTHORIZATION_REMOVED("authorization_removed"),
    LOGOUT("logout"),
    USER_REQUEST("user_request"),
    ALL_SESSIONS_REVOKED("all_sessions_revoked"),
    ACCOUNT_STATE_CHANGED("account_state_changed"),
    REFRESH_TOKEN_REUSED("refresh_token_reused"),
    EXPIRED("expired");

    private final String wireValue;

    OAuthAuthorizationInvalidationReason(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static OAuthAuthorizationInvalidationReason fromStoredValue(String value) {
        for (OAuthAuthorizationInvalidationReason reason : values()) {
            if (reason.wireValue.equals(value)) {
                return reason;
            }
        }
        throw new IllegalStateException("unsupported OAuth authorization invalidation reason");
    }

    public static OAuthAuthorizationInvalidationReason fromSessionReason(
            SessionRevokeReason reason
    ) {
        return fromStoredValue(reason.wireValue());
    }
}
