package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.OAuthClientEventType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class OAuthClientEventTypeConverter
        implements AttributeConverter<OAuthClientEventType, String> {
    @Override
    public String convertToDatabaseColumn(OAuthClientEventType value) {
        return value == null ? null : value.wireValue();
    }

    @Override
    public OAuthClientEventType convertToEntityAttribute(String value) {
        return value == null ? null : OAuthClientEventType.fromStoredValue(value);
    }
}
