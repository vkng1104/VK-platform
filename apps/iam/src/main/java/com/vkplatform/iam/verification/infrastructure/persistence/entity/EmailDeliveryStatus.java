package com.vkplatform.iam.verification.infrastructure.persistence.entity;

public enum EmailDeliveryStatus {
    PENDING("pending"),
    SENT("sent"),
    FAILED("failed");

    private final String storedValue;

    EmailDeliveryStatus(String storedValue) {
        this.storedValue = storedValue;
    }

    public String storedValue() {
        return storedValue;
    }

    public static EmailDeliveryStatus fromStoredValue(String value) {
        for (EmailDeliveryStatus status : values()) {
            if (status.storedValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalStateException("unsupported stored email delivery status");
    }
}
