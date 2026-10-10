package com.vkplatform.iam.verification.infrastructure.persistence.converter;

import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailDeliveryStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public final class EmailDeliveryStatusConverter implements AttributeConverter<EmailDeliveryStatus, String> {
    @Override
    public String convertToDatabaseColumn(EmailDeliveryStatus status) {
        return status == null ? null : status.storedValue();
    }

    @Override
    public EmailDeliveryStatus convertToEntityAttribute(String value) {
        return value == null ? null : EmailDeliveryStatus.fromStoredValue(value);
    }
}
