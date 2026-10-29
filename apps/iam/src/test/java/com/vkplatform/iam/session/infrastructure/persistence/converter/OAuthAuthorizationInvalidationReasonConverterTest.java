package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthAuthorizationInvalidationReasonConverterTest {
    private final OAuthAuthorizationInvalidationReasonConverter converter =
            new OAuthAuthorizationInvalidationReasonConverter();

    @Test
    void roundTripsEverySupportedReason() {
        for (OAuthAuthorizationInvalidationReason reason
                : OAuthAuthorizationInvalidationReason.values()) {
            assertThat(converter.convertToDatabaseColumn(reason)).isEqualTo(reason.wireValue());
            assertThat(converter.convertToEntityAttribute(reason.wireValue())).isEqualTo(reason);
        }
    }

    @Test
    void preservesNullAndRejectsUnknownReasons() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThatThrownBy(() -> converter.convertToEntityAttribute("unknown"))
                .isInstanceOf(IllegalStateException.class);
    }
}
