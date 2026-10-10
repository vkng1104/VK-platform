package com.vkplatform.iam.verification.infrastructure.persistence.converter;

import com.vkplatform.iam.verification.domain.VerificationPurpose;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationPurposeConverterTest {
    private final VerificationPurposeConverter converter = new VerificationPurposeConverter();

    @Test
    void convertsEveryPurposeToItsStoredValue() {
        assertThat(VerificationPurpose.values())
                .extracting(converter::convertToDatabaseColumn)
                .containsExactly("restricted_resource_access");
    }

    @Test
    void convertsStoredValuesToPurposes() {
        assertThat(converter.convertToEntityAttribute("restricted_resource_access"))
                .isEqualTo(VerificationPurpose.RESTRICTED_RESOURCE_ACCESS);
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
                .hasMessage("unsupported stored verification purpose");
    }
}
