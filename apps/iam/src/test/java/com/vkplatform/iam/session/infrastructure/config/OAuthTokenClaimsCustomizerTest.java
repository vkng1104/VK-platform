package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.application.port.out.SessionRepository;
import com.vkplatform.iam.session.domain.UserSession;
import com.vkplatform.iam.session.infrastructure.persistence.repository.JpaSigningKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuthTokenClaimsCustomizerTest {
    private static final Instant NOW = Instant.parse("2026-10-11T08:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("88b73dc4-c657-49e8-8f37-7f874d9582ec");
    private static final UUID SESSION_ID = UUID.fromString("68612245-a543-4b06-9406-3fdc2d62d878");

    private PasswordlessIdentityOperations identities;
    private SessionRepository sessions;
    private JpaSigningKeyStore signingKeys;
    private OAuthTokenClaimsCustomizer customizer;

    @BeforeEach
    void setUp() {
        identities = mock(PasswordlessIdentityOperations.class);
        sessions = mock(SessionRepository.class);
        signingKeys = mock(JpaSigningKeyStore.class);
        when(signingKeys.activeKeyId()).thenReturn("key-active");
        customizer = new OAuthTokenClaimsCustomizer(
                identities,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                signingKeys,
                sessions
        );
    }

    @Test
    void emitsOnlyAllowListedUserAndSessionClaims() {
        RegisteredClient client = RegisteredClient.withId("bff-registration")
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
        when(identities.activeAccount(ACCOUNT_ID))
                .thenReturn(new AuthenticatedAccount(ACCOUNT_ID, NOW.minusSeconds(1)));
        when(sessions.findOwned(ACCOUNT_ID, SESSION_ID)).thenReturn(UserSession.create(
                SESSION_ID,
                ACCOUNT_ID,
                "vk-platform-bff",
                NOW.minusSeconds(60),
                NOW.plusSeconds(180)
        ));
        JwtEncodingContext context = context(client, principal, AuthorizationGrantType.AUTHORIZATION_CODE);

        customizer.customize(context);
        JwtClaimsSet claims = context.getClaims().build();

        assertThat(context.getJwsHeader().build().getKeyId()).isEqualTo("key-active");
        assertThat(claims.getSubject()).isEqualTo(ACCOUNT_ID.toString());
        assertThat(claims.getAudience()).containsExactly("vk-platform-api");
        assertThat(claims.getClaimAsString("sid")).isEqualTo(SESSION_ID.toString());
        assertThat(claims.getClaimAsStringList("amr")).containsExactly("email_otp");
        assertThat(claims.getExpiresAt()).isEqualTo(NOW.plusSeconds(180));
        assertThat(claims.getClaims()).doesNotContainKeys("email", "role", "permissions");
    }

    @Test
    void givesServiceTokensTheirOwnAudienceAndNoUserSession() {
        RegisteredClient client = RegisteredClient.withId("service-registration")
                .clientId("vk-platform-api")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("iam.authorization.check")
                .build();
        var principal = UsernamePasswordAuthenticationToken.authenticated(
                "vk-platform-api",
                null,
                List.of()
        );
        JwtEncodingContext context = context(client, principal, AuthorizationGrantType.CLIENT_CREDENTIALS);

        customizer.customize(context);
        JwtClaimsSet claims = context.getClaims().build();

        assertThat(claims.getSubject()).isEqualTo("vk-platform-api");
        assertThat(claims.getAudience()).containsExactly("vk-platform-iam");
        assertThat(claims.getClaimAsString("client_id")).isEqualTo("vk-platform-api");
        assertThat(claims.getClaims()).doesNotContainKeys("sid", "amr", "permissions");
    }

    private static JwtEncodingContext context(
            RegisteredClient client,
            UsernamePasswordAuthenticationToken principal,
            AuthorizationGrantType grantType
    ) {
        return JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256),
                        JwtClaimsSet.builder().issuedAt(NOW).expiresAt(NOW.plusSeconds(300))
                )
                .registeredClient(client)
                .principal(principal)
                .authorizedScopes(Set.copyOf(client.getScopes()))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(grantType)
                .build();
    }

    private static SessionProperties properties() {
        return new SessionProperties(
                true,
                URI.create("https://iam.example.test"),
                "vk-platform-api",
                "vk-platform-web",
                "vk-platform-bff",
                Duration.ofMinutes(5),
                Duration.ofDays(30),
                Duration.ofDays(30),
                URI.create("https://web.example.test/sign-in"),
                "bootstrap-key",
                "",
                "",
                "",
                "b".repeat(32),
                URI.create("https://web.example.test/api/auth/callback"),
                "vk-platform-api",
                "s".repeat(32),
                "vk-platform-iam"
        );
    }
}
