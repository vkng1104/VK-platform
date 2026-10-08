package com.vkplatform.iam.verification.api;

import com.vkplatform.iam.verification.api.command.StartEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;

public interface EmailVerificationOperations {
    VerificationChallengeStarted start(StartEmailVerification command);

    EmailVerified verify(VerifyEmailCode command);
}
