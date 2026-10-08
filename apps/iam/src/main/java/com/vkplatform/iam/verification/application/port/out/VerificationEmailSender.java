package com.vkplatform.iam.verification.application.port.out;

import com.vkplatform.iam.verification.domain.EmailAddress;
import com.vkplatform.iam.verification.domain.VerificationCode;

import java.time.Instant;

public interface VerificationEmailSender {
    void send(EmailAddress recipient, VerificationCode code, Instant expiresAt);
}
