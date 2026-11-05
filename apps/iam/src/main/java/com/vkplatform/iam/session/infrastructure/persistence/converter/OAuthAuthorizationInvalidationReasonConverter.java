package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class OAuthAuthorizationInvalidationReasonConverter
        implements AttributeConverter<OAuthAuthorizationInvalidationReason, String> {
    @Override
    public String convertToDatabaseColumn(OAuthAuthorizationInvalidationReason attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public OAuthAuthorizationInvalidationReason convertToEntityAttribute(String stored) {
        return stored == null ? null : OAuthAuthorizationInvalidationReason.fromStoredValue(stored);
    }
}
