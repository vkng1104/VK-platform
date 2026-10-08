package com.vkplatform.iam.verification.web.mapper;

import com.vkplatform.iam.verification.api.command.StartEmailVerification;
import com.vkplatform.iam.verification.api.command.VerifyEmailCode;
import com.vkplatform.iam.verification.api.result.EmailVerified;
import com.vkplatform.iam.verification.api.result.VerificationChallengeStarted;
import com.vkplatform.iam.verification.web.dto.EmailVerificationChallengeEnvelope;
import com.vkplatform.iam.verification.web.dto.EmailVerificationChallengeResponse;
import com.vkplatform.iam.verification.web.dto.StartEmailVerificationRequest;
import com.vkplatform.iam.verification.web.dto.VerifiedEmailEnvelope;
import com.vkplatform.iam.verification.web.dto.VerifiedEmailResponse;
import com.vkplatform.iam.verification.web.dto.VerifyEmailRequest;
import org.springframework.stereotype.Component;

@Component
public final class EmailVerificationHttpMapper {
    public StartEmailVerification toCommand(StartEmailVerificationRequest request, String requesterAddress) {
        return new StartEmailVerification(request.email(), request.purpose(), requesterAddress);
    }

    public VerifyEmailCode toCommand(String id, VerifyEmailRequest request) {
        return new VerifyEmailCode(id, request.code());
    }

    public EmailVerificationChallengeEnvelope toResponse(VerificationChallengeStarted result) {
        return new EmailVerificationChallengeEnvelope(new EmailVerificationChallengeResponse(
                result.id(),
                result.maskedEmail(),
                result.expiresAt(),
                result.resendAfter()
        ));
    }

    public VerifiedEmailEnvelope toResponse(EmailVerified result) {
        return new VerifiedEmailEnvelope(new VerifiedEmailResponse(
                result.id(),
                result.purpose(),
                result.verifiedAt()
        ));
    }
}
