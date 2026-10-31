package com.vkplatform.iam.session.web.mapper;

import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.api.result.AuthenticationSessionPage;
import com.vkplatform.iam.session.api.result.AuthenticationSessionSummary;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.web.dto.ExchangeAuthenticationGrantRequest;
import com.vkplatform.iam.session.web.dto.SessionEnvelope;
import com.vkplatform.iam.session.web.dto.SessionResponse;
import com.vkplatform.iam.session.web.dto.SessionPageMetadata;
import com.vkplatform.iam.session.web.dto.SessionsEnvelope;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
                session.clientId(),
                session.status().wireValue(),
                session.createdAt(),
                session.lastRefreshedAt(),
                session.expiresAt(),
                session.revokedAt(),
                session.compromisedAt(),
                session.revokeReason() == null ? null : session.revokeReason().wireValue()
        ));
    }

    public SessionEnvelope toResponse(AuthenticationSessionSummary session) {
        return new SessionEnvelope(response(session));
    }

    public SessionsEnvelope toResponse(AuthenticationSessionPage sessions) {
        return new SessionsEnvelope(
                sessions.sessions().stream()
                        .map(AuthenticationSessionHttpMapper::response)
                        .toList(),
                new SessionPageMetadata(
                        sessions.page(),
                        sessions.size(),
                        sessions.totalElements(),
                        sessions.totalPages()
                )
        );
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

    private static SessionResponse response(AuthenticationSessionSummary session) {
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
