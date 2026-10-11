package com.vkplatform.iam.identity.domain;

public enum IdentityKind {
    EMAIL("email");

    private final String wireValue;

    IdentityKind(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static IdentityKind fromStoredValue(String value) {
        for (IdentityKind kind : values()) {
            if (kind.wireValue.equals(value)) {
                return kind;
            }
        }
        throw new IllegalStateException("unsupported stored identity kind");
    }
}
