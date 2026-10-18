package com.vkplatform.iam.identity.infrastructure.persistence.converter;

import com.vkplatform.iam.identity.api.event.SecurityAuditType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SecurityAuditTypeConverter implements AttributeConverter<SecurityAuditType, String> {
    @Override
    public String convertToDatabaseColumn(SecurityAuditType attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public SecurityAuditType convertToEntityAttribute(String value) {
        return value == null ? null : SecurityAuditType.fromStoredValue(value);
    }
}
