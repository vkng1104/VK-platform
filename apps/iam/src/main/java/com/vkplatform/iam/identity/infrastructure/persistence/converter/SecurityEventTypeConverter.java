package com.vkplatform.iam.identity.infrastructure.persistence.converter;

import com.vkplatform.iam.identity.api.SecurityAuditEventType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SecurityEventTypeConverter implements AttributeConverter<SecurityAuditEventType, String> {
    @Override
    public String convertToDatabaseColumn(SecurityAuditEventType attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public SecurityAuditEventType convertToEntityAttribute(String value) {
        return value == null ? null : SecurityAuditEventType.fromStoredValue(value);
    }
}
