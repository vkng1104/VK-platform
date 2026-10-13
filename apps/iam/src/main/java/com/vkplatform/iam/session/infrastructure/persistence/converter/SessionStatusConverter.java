package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.SessionStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class SessionStatusConverter implements AttributeConverter<SessionStatus, String> {
    @Override
    public String convertToDatabaseColumn(SessionStatus attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public SessionStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SessionStatus.fromStoredValue(dbData);
    }
}
