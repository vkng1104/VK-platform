package com.vkplatform.iam.identity.infrastructure.persistence.converter;

import com.vkplatform.iam.identity.domain.IdentityKind;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class IdentityKindConverter implements AttributeConverter<IdentityKind, String> {
    @Override
    public String convertToDatabaseColumn(IdentityKind attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public IdentityKind convertToEntityAttribute(String value) {
        return value == null ? null : IdentityKind.fromStoredValue(value);
    }
}
