package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.SigningKeyEventType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SigningKeyEventTypeConverter implements AttributeConverter<SigningKeyEventType, String> {
    @Override
    public String convertToDatabaseColumn(SigningKeyEventType attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public SigningKeyEventType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SigningKeyEventType.fromStoredValue(dbData);
    }
}
