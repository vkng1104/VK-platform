package com.vkplatform.iam.session.domain;

public enum OAuthClientEventType {
    REGISTERED("registered"),
    RECONCILED("reconciled"),
    CREDENTIAL_ROTATED("credential_rotated");

    private final String wireValue;

    OAuthClientEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static OAuthClientEventType fromStoredValue(String value) {
        for (OAuthClientEventType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalStateException("unsupported OAuth client event type");
    }
}
