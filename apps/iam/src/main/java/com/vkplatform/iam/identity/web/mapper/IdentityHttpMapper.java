package com.vkplatform.iam.identity.web.mapper;

import com.vkplatform.iam.identity.api.command.StartPasswordlessSignIn;
import com.vkplatform.iam.identity.api.command.VerifyPasswordlessSignIn;
import com.vkplatform.iam.identity.api.result.AuthenticationChallengeStarted;
import com.vkplatform.iam.identity.api.result.AuthenticationGrantIssued;
import com.vkplatform.iam.identity.web.dto.AuthenticationChallengeEnvelope;
import com.vkplatform.iam.identity.web.dto.AuthenticationChallengeResponse;
import com.vkplatform.iam.identity.web.dto.AuthenticationGrantEnvelope;
import com.vkplatform.iam.identity.web.dto.AuthenticationGrantResponse;
import com.vkplatform.iam.identity.web.dto.StartAuthenticationChallengeRequest;
import com.vkplatform.iam.identity.web.dto.VerifyAuthenticationChallengeRequest;
import org.springframework.stereotype.Component;

@Component
public class IdentityHttpMapper {
    public StartPasswordlessSignIn toCommand(
            StartAuthenticationChallengeRequest request,
            String requesterAddress
    ) {
        return new StartPasswordlessSignIn(request.email(), requesterAddress);
    }

    public VerifyPasswordlessSignIn toCommand(
            String challengeId,
            VerifyAuthenticationChallengeRequest request,
            String requestId
    ) {
        return new VerifyPasswordlessSignIn(challengeId, request.code(), requestId);
    }

    public AuthenticationChallengeEnvelope toResponse(AuthenticationChallengeStarted result) {
        return new AuthenticationChallengeEnvelope(new AuthenticationChallengeResponse(
                result.id(),
                result.maskedEmail(),
                result.expiresAt(),
                result.resendAfter()
        ));
    }

    public AuthenticationGrantEnvelope toResponse(AuthenticationGrantIssued result) {
        return new AuthenticationGrantEnvelope(new AuthenticationGrantResponse(
                result.accountId(),
                result.grant(),
                result.audience(),
                result.expiresAt()
        ));
    }
}
