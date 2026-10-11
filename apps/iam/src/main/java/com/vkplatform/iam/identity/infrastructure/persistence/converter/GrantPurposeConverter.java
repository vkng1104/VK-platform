package com.vkplatform.iam.identity.infrastructure.persistence.converter;

import com.vkplatform.iam.identity.domain.GrantPurpose;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class GrantPurposeConverter implements AttributeConverter<GrantPurpose, String> {
    @Override
    public String convertToDatabaseColumn(GrantPurpose attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public GrantPurpose convertToEntityAttribute(String value) {
        return value == null ? null : GrantPurpose.fromStoredValue(value);
    }
}
