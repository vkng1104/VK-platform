package com.vkplatform.iam.verification.domain;

public enum VerificationPurpose {
    RESTRICTED_RESOURCE_ACCESS("restricted_resource_access");

    private final String wireValue;

    VerificationPurpose(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static VerificationPurpose fromWireValue(String value) {
        for (VerificationPurpose purpose : values()) {
            if (purpose.wireValue.equals(value)) {
                return purpose;
            }
        }
        throw VerificationFailure.invalidPurpose();
    }
}
