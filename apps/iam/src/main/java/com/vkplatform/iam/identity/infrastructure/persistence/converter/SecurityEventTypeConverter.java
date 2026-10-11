package com.vkplatform.iam.identity.infrastructure.persistence.converter;

import com.vkplatform.iam.identity.domain.SecurityEventType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SecurityEventTypeConverter implements AttributeConverter<SecurityEventType, String> {
    @Override
    public String convertToDatabaseColumn(SecurityEventType attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public SecurityEventType convertToEntityAttribute(String value) {
        return value == null ? null : SecurityEventType.fromStoredValue(value);
    }
}
