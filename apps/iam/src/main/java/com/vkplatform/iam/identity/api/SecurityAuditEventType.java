package com.vkplatform.iam.identity.api;

public enum SecurityAuditEventType {
    ACCOUNT_CREATED("account_created"),
    LOGIN_IDENTITY_LINKED("login_identity_linked"),
    SIGN_IN_GRANT_ISSUED("sign_in_grant_issued"),
    SIGN_IN_GRANT_CONSUMED("sign_in_grant_consumed"),
    ACCOUNT_SUSPENDED("account_suspended"),
    ACCOUNT_REACTIVATED("account_reactivated"),
    ACCOUNT_DELETED("account_deleted"),
    SESSION_CREATED("session_created"),
    REFRESH_TOKEN_ROTATED("refresh_token_rotated"),
    REFRESH_TOKEN_REUSED("refresh_token_reused"),
    SESSION_REVOKED("session_revoked"),
    ALL_SESSIONS_REVOKED("all_sessions_revoked");

    private final String wireValue;

    SecurityAuditEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SecurityAuditEventType fromStoredValue(String value) {
        for (SecurityAuditEventType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalStateException("unsupported stored security audit event type");
    }
}
