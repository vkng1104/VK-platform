package com.vkplatform.iam.session.web.mapper;

import com.vkplatform.iam.session.api.command.ExchangeSignInGrant;
import com.vkplatform.iam.session.api.result.SessionSummary;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.web.dto.ExchangeAuthenticationGrantRequest;
import com.vkplatform.iam.session.web.dto.SessionEnvelope;
import com.vkplatform.iam.session.web.dto.SessionResponse;
import com.vkplatform.iam.session.web.dto.SessionsEnvelope;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class SessionHttpMapper {
    public ExchangeSignInGrant toCommand(
            ExchangeAuthenticationGrantRequest request,
            String requestId
    ) {
        return new ExchangeSignInGrant(request.grant(), request.audience(), requestId);
    }

    public SessionEnvelope toResponse(SessionSummary session) {
        return new SessionEnvelope(response(session));
    }

    public SessionsEnvelope toResponse(List<SessionSummary> sessions) {
        return new SessionsEnvelope(sessions.stream().map(SessionHttpMapper::response).toList());
    }

    public PrincipalIds principal(JwtAuthenticationToken authentication) {
        try {
            return new PrincipalIds(
                    UUID.fromString(authentication.getToken().getSubject()),
                    UUID.fromString(authentication.getToken().getClaimAsString("sid"))
            );
        } catch (RuntimeException exception) {
            throw SessionFailure.sessionUnavailable();
        }
    }

    private static SessionResponse response(SessionSummary session) {
        return new SessionResponse(
                session.id(),
                session.clientId(),
                session.status(),
                session.createdAt(),
                session.lastRefreshedAt(),
                session.expiresAt(),
                session.revokedAt(),
                session.compromisedAt(),
                session.revokeReason()
        );
    }

    public record PrincipalIds(UUID accountId, UUID sessionId) {
    }
}
