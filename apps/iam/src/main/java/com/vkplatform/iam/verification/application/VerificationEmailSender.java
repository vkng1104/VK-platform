package com.vkplatform.iam.verification.application;

import java.time.Instant;

public interface VerificationEmailSender {
    void send(String recipient, String code, Instant expiresAt);
}
