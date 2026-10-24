package com.vkplatform.iam.session.web.mapper;

import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.web.dto.ExchangeAuthenticationGrantRequest;
import com.vkplatform.iam.session.web.dto.SessionEnvelope;
import com.vkplatform.iam.session.web.dto.SessionResponse;
import org.springframework.stereotype.Component;

@Component
public class AuthenticationSessionHttpMapper {
    public ExchangeAuthenticationGrant toCommand(
            ExchangeAuthenticationGrantRequest request,
            String requestId
    ) {
        return new ExchangeAuthenticationGrant(request.grant(), request.audience(), requestId);
    }

    public SessionEnvelope toResponse(AuthenticationSession session) {
        return new SessionEnvelope(new SessionResponse(
                session.id(),
                session.status().wireValue(),
                session.createdAt(),
                session.expiresAt()
        ));
    }
}
