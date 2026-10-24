package com.vkplatform.iam.session.domain;

public enum SigningKeyEventType {
    BOOTSTRAPPED("bootstrapped"),
    ACTIVATED("activated"),
    RETIRED("retired"),
    COMPROMISED("compromised"),
    REENCRYPTED("reencrypted");

    private final String wireValue;

    SigningKeyEventType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SigningKeyEventType fromStoredValue(String value) {
        for (SigningKeyEventType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalStateException("unsupported signing-key event type");
    }
}
