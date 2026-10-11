package com.vkplatform.iam.identity.domain;

public enum GrantPurpose {
    SIGN_IN("sign_in");

    private final String wireValue;

    GrantPurpose(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static GrantPurpose fromStoredValue(String value) {
        for (GrantPurpose purpose : values()) {
            if (purpose.wireValue.equals(value)) {
                return purpose;
            }
        }
        throw new IllegalStateException("unsupported stored grant purpose");
    }
}
