package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.SessionRevokeReason;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class SessionRevokeReasonConverter implements AttributeConverter<SessionRevokeReason, String> {
    @Override
    public String convertToDatabaseColumn(SessionRevokeReason attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public SessionRevokeReason convertToEntityAttribute(String dbData) {
        return dbData == null ? null : SessionRevokeReason.fromStoredValue(dbData);
    }
}
