package com.vkplatform.iam.verification.infrastructure.persistence.converter;

import com.vkplatform.iam.verification.domain.VerificationPurpose;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public final class VerificationPurposeConverter implements AttributeConverter<VerificationPurpose, String> {
    @Override
    public String convertToDatabaseColumn(VerificationPurpose purpose) {
        return purpose == null ? null : purpose.wireValue();
    }

    @Override
    public VerificationPurpose convertToEntityAttribute(String value) {
        return value == null ? null : VerificationPurpose.fromStoredValue(value);
    }
}
