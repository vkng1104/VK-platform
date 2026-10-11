package com.vkplatform.iam.identity.infrastructure.persistence.converter;

import com.vkplatform.iam.identity.domain.AccountStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class AccountStatusConverter implements AttributeConverter<AccountStatus, String> {
    @Override
    public String convertToDatabaseColumn(AccountStatus attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public AccountStatus convertToEntityAttribute(String value) {
        return value == null ? null : AccountStatus.fromStoredValue(value);
    }
}
