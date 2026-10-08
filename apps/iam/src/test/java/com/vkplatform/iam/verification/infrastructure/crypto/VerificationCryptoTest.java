package com.vkplatform.iam.verification.infrastructure.crypto;

import com.vkplatform.iam.verification.domain.EmailAddress;
import com.vkplatform.iam.verification.domain.VerificationCode;
import com.vkplatform.iam.verification.domain.VerificationPurpose;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationCryptoTest {
    private static final byte[] OTP_PEPPER = "otp-pepper-must-be-at-least-32-bytes"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINGERPRINT_KEY = "fingerprint-key-must-be-32-bytes!!"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void generatesZeroPaddedSixDigitCodes() {
        SecureVerificationCodeGenerator generator = new SecureVerificationCodeGenerator(new FixedSecureRandom());

        assertThat(generator.generate().value()).isEqualTo("000042");
    }

    @Test
    void producesDeterministicPurposeBoundDigestsWithoutReusingFingerprintKeys() {
        HmacVerificationCodeHasher hasher = new HmacVerificationCodeHasher(OTP_PEPPER, FINGERPRINT_KEY);
        UUID id = UUID.fromString("123e4567-e89b-42d3-a456-426614174000");
        VerificationCode code = new VerificationCode("123456");

        byte[] first = hasher.codeDigest(id, VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, code);
        byte[] second = hasher.codeDigest(id, VerificationPurpose.RESTRICTED_RESOURCE_ACCESS, code);
        byte[] emailFingerprint = hasher.emailFingerprint(new EmailAddress("reader@example.com"));
        byte[] requesterFingerprint = hasher.requesterFingerprint("192.0.2.15");

        assertThat(first).hasSize(32).isEqualTo(second);
        assertThat(emailFingerprint).hasSize(32).isNotEqualTo(first);
        assertThat(requesterFingerprint).hasSize(32).isNotEqualTo(first).isNotEqualTo(emailFingerprint);
    }

    private static final class FixedSecureRandom extends SecureRandom {
        @Override
        public int nextInt(int bound) {
            return 42;
        }
    }
}
