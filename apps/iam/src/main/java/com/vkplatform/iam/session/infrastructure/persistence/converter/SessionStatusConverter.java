package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.SessionStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SessionStatusConverter implements AttributeConverter<SessionStatus, String> {
    @Override
    public String convertToDatabaseColumn(SessionStatus attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public SessionStatus convertToEntityAttribute(String stored) {
        return stored == null ? null : SessionStatus.fromStoredValue(stored);
    }
}
