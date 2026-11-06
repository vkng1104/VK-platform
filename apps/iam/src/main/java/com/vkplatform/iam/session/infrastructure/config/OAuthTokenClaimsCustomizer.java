package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.application.service.OAuthTokenLifecycleService;
import com.vkplatform.iam.session.application.service.OAuthTokenLifecycleService.AccessTokenWindow;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyRuntime;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

final class OAuthTokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {
    private final OAuthTokenLifecycleService tokenLifecycle;
    private final OAuthAuthorizationProperties properties;
    private final SigningKeyRuntime signingKeys;

    OAuthTokenClaimsCustomizer(
            OAuthTokenLifecycleService tokenLifecycle,
            OAuthAuthorizationProperties properties,
            SigningKeyRuntime signingKeys
    ) {
        this.tokenLifecycle = tokenLifecycle;
        this.properties = properties;
        this.signingKeys = signingKeys;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        Authentication principal = context.getPrincipal();
        UUID accountId = parseUuid(principal.getName(), "authenticated account id");
        Map<?, ?> details = principal.getDetails() instanceof Map<?, ?> values ? values : Map.of();
        UUID sessionId = parseUuid(requireDetail(details, "session_id"), "authenticated session id");
        String authenticationMethod = requireDetail(details, "amr");
        AccessTokenWindow window = tokenLifecycle.accessTokenWindow(
                accountId,
                sessionId,
                context.getRegisteredClient().getClientId()
        );

        context.getJwsHeader().keyId(signingKeys.activeSigningKeyId());
        context.getClaims()
                .subject(accountId.toString())
                .audience(List.of(properties.audience()))
                .notBefore(window.notBefore())
                .expiresAt(window.expiresAt())
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
