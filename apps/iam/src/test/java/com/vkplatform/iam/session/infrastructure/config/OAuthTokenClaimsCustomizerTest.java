package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.application.service.OAuthTokenLifecycleService;
import com.vkplatform.iam.session.application.service.OAuthTokenLifecycleService.AccessTokenWindow;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OAuthTokenClaimsCustomizerTest {
    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("88b73dc4-c657-49e8-8f37-7f874d9582ec");
    private static final UUID SESSION_ID = UUID.fromString("68612245-a543-4b06-9406-3fdc2d62d878");

    private OAuthTokenLifecycleService tokenLifecycle;
    private SigningKeyRuntime signingKeys;
    private OAuthTokenClaimsCustomizer customizer;

    @BeforeEach
    void setUp() {
        tokenLifecycle = mock(OAuthTokenLifecycleService.class);
        signingKeys = mock(SigningKeyRuntime.class);
        when(signingKeys.activeSigningKeyId()).thenReturn("oauth-signing-2026");
        customizer = new OAuthTokenClaimsCustomizer(
                tokenLifecycle,
                new OAuthAuthorizationProperties(
                        URI.create("https://iam.example.test"),
                        "vk-platform-api",
                        URI.create("https://web.example.test/sign-in"),
                        URI.create("https://web.example.test/api/auth/callback"),
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(5),
                        Duration.ofDays(30)
                ),
                clientProperties(),
                signingKeys
        );
    }

    @Test
    void emitsOnlyAllowListedAccountAndSessionClaimsInsideTheServicesWindow() {
        when(tokenLifecycle.accessTokenWindow(ACCOUNT_ID, SESSION_ID, "vk-platform-bff"))
                .thenReturn(new AccessTokenWindow(NOW.plusSeconds(5), NOW.plusSeconds(180)));
        JwtEncodingContext context = context(OAuth2TokenType.ACCESS_TOKEN);

        customizer.customize(context);
        JwtClaimsSet claims = context.getClaims().build();

        assertThat(context.getJwsHeader().build().getKeyId()).isEqualTo("oauth-signing-2026");
        assertThat(claims.getSubject()).isEqualTo(ACCOUNT_ID.toString());
        assertThat(claims.getAudience()).containsExactly("vk-platform-api");
        assertThat(claims.getClaimAsString("client_id")).isEqualTo("vk-platform-bff");
        assertThat(claims.getClaimAsString("sid")).isEqualTo(SESSION_ID.toString());
        assertThat(claims.getClaimAsStringList("amr")).containsExactly("email_otp");
        assertThat(claims.getNotBefore()).isEqualTo(NOW.plusSeconds(5));
        assertThat(claims.getExpiresAt()).isEqualTo(NOW.plusSeconds(180));
        assertThat(claims.getClaims()).doesNotContainKeys("email", "role", "permissions");
    }

    @Test
    void refusesToIssueWhenTheServiceRefuses() {
        when(tokenLifecycle.accessTokenWindow(ACCOUNT_ID, SESSION_ID, "vk-platform-bff"))
                .thenThrow(new IllegalStateException("OAuth browser session is unavailable"));

        assertThatThrownBy(() -> customizer.customize(context(OAuth2TokenType.ACCESS_TOKEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session is unavailable");
    }

    @Test
    void emitsServiceClaimsWithoutUserSessionClaimsForClientCredentials() {
        RegisteredClient service = RegisteredClient.withId("service:vk-platform-service")
                .clientId("vk-platform-service")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("iam.authorization.check")
                .scope("iam.relationship.write")
                .build();
        JwtEncodingContext context = JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256),
                        JwtClaimsSet.builder().issuedAt(NOW).expiresAt(NOW.plusSeconds(300))
                )
                .registeredClient(service)
                .principal(UsernamePasswordAuthenticationToken.authenticated(
                        "vk-platform-service",
                        null,
                        List.of()
                ))
                .authorizedScopes(Set.copyOf(service.getScopes()))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .build();

        customizer.customize(context);
        JwtClaimsSet claims = context.getClaims().build();

        assertThat(claims.getSubject()).isEqualTo("vk-platform-service");
        assertThat(claims.getAudience()).containsExactly("vk-platform-services");
        assertThat(claims.getClaimAsString("client_id")).isEqualTo("vk-platform-service");
        assertThat(claims.getClaims()).doesNotContainKeys("sid", "amr");
        verifyNoInteractions(tokenLifecycle);
    }

    @Test
    void leavesOtherTokenTypesAlone() {
        customizer.customize(context(OAuth2TokenType.REFRESH_TOKEN));

        verifyNoInteractions(tokenLifecycle);
    }

    private static JwtEncodingContext context(OAuth2TokenType tokenType) {
        RegisteredClient client = RegisteredClient.withId("bff:vk-platform-bff")
                .clientId("vk-platform-bff")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://web.example.test/api/auth/callback")
                .scope("api.access")
                .build();
        UsernamePasswordAuthenticationToken principal = UsernamePasswordAuthenticationToken.authenticated(
                ACCOUNT_ID.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        Map<String, String> details = new HashMap<>();
        details.put("session_id", SESSION_ID.toString());
        details.put("amr", "email_otp");
        principal.setDetails(details);
        return JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256),
                        JwtClaimsSet.builder().issuedAt(NOW).expiresAt(NOW.plusSeconds(300))
                )
                .registeredClient(client)
                .principal(principal)
                .authorizedScopes(Set.copyOf(client.getScopes()))
                .tokenType(tokenType)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .build();
    }

    private static OAuthClientProperties clientProperties() {
        return new OAuthClientProperties(
                "vk-platform-service",
                "s".repeat(32),
                "vk-platform-services",
                "iam.authorization.check,iam.relationship.write",
                "",
                "",
                "",
                "",
                "",
                null
        );
    }
}
