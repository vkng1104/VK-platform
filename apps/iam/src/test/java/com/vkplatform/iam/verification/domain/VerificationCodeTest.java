package com.vkplatform.iam.verification.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerificationCodeTest {
    @Test
    void acceptsExactlySixDigits() {
        assertThat(new VerificationCode("012345").value()).isEqualTo("012345");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "12345", "1234567", "12345a"})
    void rejectsInvalidCodes(String value) {
        assertThatThrownBy(() -> new VerificationCode(value))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_CODE_FORMAT));
    }
}
