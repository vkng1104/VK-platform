package com.vkplatform.iam.session.infrastructure.persistence.converter;

import com.vkplatform.iam.session.domain.SessionStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionStatusConverterTest {
    private final SessionStatusConverter converter = new SessionStatusConverter();

    @Test
    void storesTheSingleSupportedStatusUsingItsWireValue() {
        assertThat(converter.convertToDatabaseColumn(SessionStatus.ACTIVE)).isEqualTo("active");
        assertThat(converter.convertToEntityAttribute("active")).isEqualTo(SessionStatus.ACTIVE);
    }

    @Test
    void rejectsUnimplementedFutureStatuses() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("revoked"))
                .isInstanceOf(IllegalStateException.class);
    }
}
