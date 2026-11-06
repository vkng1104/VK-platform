package com.vkplatform.iam.identity.domain;

public enum SecurityEventType {
    ACCOUNT_CREATED("account_created"),
    LOGIN_IDENTITY_LINKED("login_identity_linked"),
    SIGN_IN_GRANT_ISSUED("sign_in_grant_issued"),
    SIGN_IN_GRANT_CONSUMED("sign_in_grant_consumed"),
    ACCOUNT_SUSPENDED("account_suspended"),
    ACCOUNT_REACTIVATED("account_reactivated"),
    ACCOUNT_DELETED("account_deleted");

    private final String wireValue;

    SecurityEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SecurityEventType fromStoredValue(String value) {
        for (SecurityEventType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalStateException("unsupported stored security event type");
    }
}
