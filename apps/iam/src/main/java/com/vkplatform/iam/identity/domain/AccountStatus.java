package com.vkplatform.iam.identity.domain;

public enum AccountStatus {
    ACTIVE("active"),
    SUSPENDED("suspended"),
    RECOVERY_LOCKED("recovery_locked"),
    DELETED("deleted");

    private final String wireValue;

    AccountStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static AccountStatus fromStoredValue(String value) {
        for (AccountStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalStateException("unsupported stored account status");
    }
}
