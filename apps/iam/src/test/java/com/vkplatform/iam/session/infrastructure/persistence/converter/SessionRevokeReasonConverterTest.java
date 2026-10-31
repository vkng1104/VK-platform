package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.SessionRevokeReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionRevokeReasonConverterTest {
    private final SessionRevokeReasonConverter converter = new SessionRevokeReasonConverter();

    @Test
    void roundTripsEverySupportedReason() {
        for (SessionRevokeReason reason : SessionRevokeReason.values()) {
            assertThat(converter.convertToDatabaseColumn(reason)).isEqualTo(reason.wireValue());
            assertThat(converter.convertToEntityAttribute(reason.wireValue())).isEqualTo(reason);
        }
    }

    @Test
    void preservesNullAndRejectsUnknownReasons() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThatThrownBy(() -> converter.convertToEntityAttribute("refresh_token_reuse"))
                .isInstanceOf(IllegalStateException.class);
    }
}
