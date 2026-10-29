package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyRuntime;
import org.springframework.security.core.Authentication;
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
    private final AuthenticationSessionRepository sessions;
    private final OAuthAuthorizationProperties properties;
    private final OAuthClientProperties clients;
    private final SigningKeyRuntime signingKeys;
    private final Clock clock;

    OAuthTokenClaimsCustomizer(
            PasswordlessIdentityOperations identities,
            AuthenticationSessionRepository sessions,
            OAuthAuthorizationProperties properties,
            OAuthClientProperties clients,
            SigningKeyRuntime signingKeys,
            Clock clock
    ) {
        this.identities = identities;
        this.sessions = sessions;
        this.properties = properties;
        this.clients = clients;
        this.signingKeys = signingKeys;
        this.clock = clock;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        context.getJwsHeader().keyId(signingKeys.activeSigningKeyId());
        if (org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS
                .equals(context.getAuthorizationGrantType())) {
            String clientId = context.getRegisteredClient().getClientId();
            context.getClaims()
                    .subject(clientId)
                    .audience(List.of(clients.serviceAudience()))
                    .claim("client_id", clientId);
            return;
        }
        Authentication principal = context.getPrincipal();
        UUID accountId = parseUuid(principal.getName(), "authenticated account id");
        AuthenticatedAccount account = identities.activeAccount(accountId);
        Map<?, ?> details = principal.getDetails() instanceof Map<?, ?> values ? values : Map.of();
        UUID sessionId = parseUuid(requireDetail(details, "session_id"), "authenticated session id");
        String authenticationMethod = requireDetail(details, "amr");
        Instant now = clock.instant();
        AuthenticationSession session = sessions.findById(sessionId)
                .filter(candidate -> candidate.canAuthorize(
                        accountId,
                        context.getRegisteredClient().getClientId(),
                        now
                ))
                .orElseThrow(() -> new IllegalStateException("OAuth browser session is unavailable"));
        Instant notBefore = account.tokenNotBefore().isAfter(now) ? account.tokenNotBefore() : now;
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        if (session.expiresAt().isBefore(expiresAt)) {
            expiresAt = session.expiresAt();
        }

        context.getClaims()
                .subject(accountId.toString())
                .audience(List.of(properties.audience()))
                .notBefore(notBefore)
                .expiresAt(expiresAt)
                .claim("client_id", context.getRegisteredClient().getClientId())
                .claim("sid", sessionId.toString())
                .claim("amr", List.of(authenticationMethod));
    }

    private static String requireDetail(Map<?, ?> details, String name) {
        Object value = details.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("authenticated session detail is missing: " + name);
        }
        return value.toString();
    }

    private static UUID parseUuid(String value, String name) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " is invalid", exception);
        }
    }
}
