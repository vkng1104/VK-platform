package com.vkplatform.iam.verification.infrastructure.persistence.converter;

import com.vkplatform.iam.verification.infrastructure.persistence.entity.EmailDeliveryStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailDeliveryStatusConverterTest {
    private final EmailDeliveryStatusConverter converter = new EmailDeliveryStatusConverter();

    @Test
    void convertsEveryDeliveryStatusToItsStoredValue() {
        assertThat(EmailDeliveryStatus.values())
                .extracting(converter::convertToDatabaseColumn)
                .containsExactly("pending", "sent", "failed");
    }

    @Test
    void convertsEveryStoredValueToItsDeliveryStatus() {
        assertThat(converter.convertToEntityAttribute("pending")).isEqualTo(EmailDeliveryStatus.PENDING);
        assertThat(converter.convertToEntityAttribute("sent")).isEqualTo(EmailDeliveryStatus.SENT);
        assertThat(converter.convertToEntityAttribute("failed")).isEqualTo(EmailDeliveryStatus.FAILED);
    }

    @Test
    void preservesNullValues() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void rejectsUnknownStoredValues() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("unknown"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unsupported stored email delivery status");
    }
}
