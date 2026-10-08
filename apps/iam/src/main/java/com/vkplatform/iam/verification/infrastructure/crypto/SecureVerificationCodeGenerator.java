package com.vkplatform.iam.verification.infrastructure.crypto;

import com.vkplatform.iam.verification.application.port.out.VerificationCodeGenerator;
import com.vkplatform.iam.verification.domain.VerificationCode;

import java.security.SecureRandom;
import java.util.Objects;

public final class SecureVerificationCodeGenerator implements VerificationCodeGenerator {
    private final SecureRandom random;

    public SecureVerificationCodeGenerator(SecureRandom random) {
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public VerificationCode generate() {
        return new VerificationCode("%06d".formatted(random.nextInt(1_000_000)));
    }
}
