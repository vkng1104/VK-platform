package com.vkplatform.iam.session.domain;

public enum SessionStatus {
    ACTIVE("active"),
    REVOKED("revoked"),
    COMPROMISED("compromised");

    private final String wireValue;

    SessionStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SessionStatus fromStoredValue(String value) {
        for (SessionStatus status : values()) {
            if (status.wireValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalStateException("unsupported stored session status");
    }
}
