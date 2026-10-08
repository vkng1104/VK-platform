package com.vkplatform.iam.verification.application.port.out;

import com.vkplatform.iam.verification.domain.EmailAddress;
import com.vkplatform.iam.verification.domain.VerificationCode;
import com.vkplatform.iam.verification.domain.VerificationPurpose;

import java.util.UUID;

public interface VerificationCodeHasher {
    byte[] emailFingerprint(EmailAddress email);

    byte[] requesterFingerprint(String requesterAddress);

    byte[] codeDigest(UUID id, VerificationPurpose purpose, VerificationCode code);
}
