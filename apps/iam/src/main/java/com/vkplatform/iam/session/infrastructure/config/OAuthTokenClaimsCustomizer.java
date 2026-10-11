package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.infrastructure.persistence.repository.JpaSigningKeyStore;
import com.vkplatform.iam.session.application.port.out.SessionRepository;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.domain.UserSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class OAuthTokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {
    private final PasswordlessIdentityOperations identities;
    private final SessionProperties properties;
    private final Clock clock;
    private final JpaSigningKeyStore signingKeys;
    private final SessionRepository sessions;

    OAuthTokenClaimsCustomizer(
            PasswordlessIdentityOperations identities,
            SessionProperties properties,
            Clock clock,
            JpaSigningKeyStore signingKeys,
            SessionRepository sessions
    ) {
        this.identities = identities;
        this.properties = properties;
        this.clock = clock;
        this.signingKeys = signingKeys;
        this.sessions = sessions;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        context.getJwsHeader().keyId(signingKeys.activeKeyId());
        context.getClaims().claim("client_id", context.getRegisteredClient().getClientId());
        if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(context.getAuthorizationGrantType())) {
            context.getClaims()
                    .subject(context.getRegisteredClient().getClientId())
                    .audience(List.of(properties.serviceAudience()));
            return;
        }

        Authentication principal = context.getPrincipal();
        UUID accountId = UUID.fromString(principal.getName());
        AuthenticatedAccount account = identities.activeAccount(accountId);
        Map<?, ?> details = principal.getDetails() instanceof Map<?, ?> values ? values : Map.of();
        String sessionId = requireDetail(details, "session_id");
        String authenticationMethod = requireDetail(details, "amr");
        Instant now = clock.instant();
        UserSession session = sessions.findOwned(account.accountId(), UUID.fromString(sessionId));
        if (!session.canUse(context.getRegisteredClient().getClientId(), now)) {
            throw SessionFailure.sessionUnavailable();
        }
        Instant notBefore = account.tokenNotBefore().isAfter(now) ? account.tokenNotBefore() : now;
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        if (session.expiresAt().isBefore(expiresAt)) {
            expiresAt = session.expiresAt();
        }
        context.getClaims()
                .subject(account.accountId().toString())
                .audience(List.of(properties.audience()))
                .notBefore(notBefore)
                .expiresAt(expiresAt)
                .claim("sid", sessionId)
                .claim("amr", List.of(authenticationMethod));
    }

    private static String requireDetail(Map<?, ?> details, String name) {
        Object value = details.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("authenticated session detail is missing: " + name);
        }
        return value.toString();
    }
}
