package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.SigningKeyEventType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SigningKeyEventTypeConverter implements AttributeConverter<SigningKeyEventType, String> {
    @Override
    public String convertToDatabaseColumn(SigningKeyEventType value) {
        return value == null ? null : value.wireValue();
    }

    @Override
    public SigningKeyEventType convertToEntityAttribute(String value) {
        return value == null ? null : SigningKeyEventType.fromStoredValue(value);
    }
}
