package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class OAuthAuthorizationInvalidationReasonConverter
        implements AttributeConverter<OAuthAuthorizationInvalidationReason, String> {
    @Override
    public String convertToDatabaseColumn(OAuthAuthorizationInvalidationReason value) {
        return value == null ? null : value.wireValue();
    }

    @Override
    public OAuthAuthorizationInvalidationReason convertToEntityAttribute(String value) {
        return value == null ? null : OAuthAuthorizationInvalidationReason.fromStoredValue(value);
    }
}
