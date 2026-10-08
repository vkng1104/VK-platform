package com.vkplatform.iam.verification.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailAddressTest {
    @Test
    void normalizesTheDomainAndPreservesTheLocalPart() {
        EmailAddress email = new EmailAddress(" Reviewer@EXAMPLE.COM ");

        assertThat(email.value()).isEqualTo("Reviewer@example.com");
        assertThat(email.masked()).isEqualTo("R******r@example.com");
    }

    @Test
    void masksSingleCharacterLocalParts() {
        assertThat(new EmailAddress("a@example.com").masked()).isEqualTo("*@example.com");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "not-an-email", "reader@localhost", "reader\n@example.com"})
    void rejectsInvalidAddresses(String value) {
        assertThatThrownBy(() -> new EmailAddress(value))
                .isInstanceOfSatisfying(VerificationFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(VerificationFailure.Reason.INVALID_EMAIL));
    }
}
