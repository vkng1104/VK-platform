package com.vkplatform.iam.verification.application.port.out;

import com.vkplatform.iam.verification.domain.VerificationCode;

public interface VerificationCodeGenerator {
    VerificationCode generate();
}
