package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.infrastructure.persistence.repository.JpaPasswordlessIdentityRepository;
import com.vkplatform.iam.session.domain.SessionStatus;
import com.vkplatform.iam.session.domain.UserSession;
import com.vkplatform.iam.session.infrastructure.crypto.TokenDigest;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity_;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientEntity_;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthRefreshTokenEntity_;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity_;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "iam.session.enabled=false",
        "iam.email.cleanup-enabled=false"
})
class JpaSessionTokenLifecycleIntegrationTest {
    private static final String CLIENT_REGISTRATION_ID = "bff-registration";
    private static final String CLIENT_ID = "vk-platform-bff";

    @Autowired
    private JpaPasswordlessIdentityRepository identities;

    @Autowired
    private JpaSessionRepository sessions;

    @Autowired
    private JpaRegisteredClientRepository clients;

    @Autowired
    private OAuth2AuthorizationService authorizations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_client_events");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_clients");
        jdbcTemplate.update("DELETE FROM iam_session.signing_key_events");
        jdbcTemplate.update("DELETE FROM iam_session.signing_keys");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
    }

    @Test
    void flywaySchemaMatchesGeneratedSessionMappings() {
        assertThat(SessionEntity_.id).isNotNull();
        assertThat(OAuthClientEntity_.clientId).isNotNull();
        assertThat(OAuthAuthorizationEntity_.refreshTokenDigest).isNotNull();
        assertThat(OAuthRefreshTokenEntity_.familyId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.signing_key_operation_guard",
                Integer.class
        )).isEqualTo(1);
    }

    @Test
    void storesOnlyDigestsAndCompromisesSessionWhenConsumedRefreshTokenIsReplayed() {
        Instant now = Instant.now().minusSeconds(1);
        UUID accountId = identities.issueSignInGrant(
                bytes(1),
                bytes(2),
                "vk-platform-web",
                now.plus(Duration.ofMinutes(5)),
                now,
                "request-account"
        ).accountId();
        UUID sessionId = UUID.randomUUID();
        sessions.create(
                UserSession.create(sessionId, accountId, CLIENT_ID, now, now.plus(Duration.ofDays(30))),
                "request-session"
        );
        RegisteredClient client = registeredClient();
        clients.save(client);

        String accessToken = "access-token-that-must-not-be-stored";
        String firstRefreshToken = "first-refresh-token-that-must-not-be-stored";
        OAuth2Authorization initial = authorization(
                client,
                accountId,
                sessionId,
                accessToken,
                firstRefreshToken,
                now
        );
        authorizations.save(initial);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT access_token_digest FROM iam_session.oauth_authorizations WHERE id = ?",
                byte[].class,
                initial.getId()
        )).containsExactly(TokenDigest.sha256(accessToken));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT refresh_token_digest FROM iam_session.oauth_authorizations WHERE id = ?",
                byte[].class,
                initial.getId()
        )).containsExactly(TokenDigest.sha256(firstRefreshToken));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_digest FROM iam_session.oauth_refresh_tokens WHERE authorization_id = ?",
                byte[].class,
                initial.getId()
        )).containsExactly(TokenDigest.sha256(firstRefreshToken));
        String persistedAttributes = jdbcTemplate.queryForObject(
                "SELECT attributes FROM iam_session.oauth_authorizations WHERE id = ?",
                String.class,
                initial.getId()
        );
        assertThat(persistedAttributes)
                .doesNotContain("state-that-must-not-be-stored")
                .doesNotContain(accessToken)
                .doesNotContain(firstRefreshToken);

        OAuth2Authorization loadedByState = authorizations.findByToken(
                "state-that-must-not-be-stored",
                null
        );
        assertThat(loadedByState.<String>getAttribute(OAuth2ParameterNames.STATE))
                .isEqualTo("state-that-must-not-be-stored");
        OAuth2AuthorizationRequest restoredRequest = loadedByState.getAttribute(
                OAuth2AuthorizationRequest.class.getName()
        );
        assertThat(restoredRequest.getState()).isEqualTo("state-that-must-not-be-stored");

        OAuth2Authorization loaded = authorizations.findByToken(
                firstRefreshToken,
                OAuth2TokenType.REFRESH_TOKEN
        );
        assertThat(loaded).isNotNull();
        assertThat(loaded.getRefreshToken().getToken().getTokenValue()).isEqualTo(firstRefreshToken);

        String secondRefreshToken = "second-refresh-token-that-must-not-be-stored";
        OAuth2Authorization rotated = OAuth2Authorization.from(loaded)
                .accessToken(new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        "rotated-access-token",
                        now.plusSeconds(1),
                        now.plus(Duration.ofMinutes(5)),
                        Set.of("api.access")
                ))
                .refreshToken(new OAuth2RefreshToken(
                        secondRefreshToken,
                        now.plusSeconds(1),
                        now.plus(Duration.ofDays(30))
                ))
                .build();
        authorizations.save(rotated);

        Map<String, Object> lineage = jdbcTemplate.queryForMap("""
                SELECT consumed_at, replacement_id
                FROM iam_session.oauth_refresh_tokens
                WHERE token_digest = ?
                """, TokenDigest.sha256(firstRefreshToken));
        assertThat(lineage.get("consumed_at")).isNotNull();
        assertThat(lineage.get("replacement_id")).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.oauth_refresh_tokens WHERE authorization_id = ?",
                Integer.class,
                initial.getId()
        )).isEqualTo(2);

        assertThat(authorizations.findByToken(firstRefreshToken, OAuth2TokenType.REFRESH_TOKEN)).isNull();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                sessionId
        )).isEqualTo(SessionStatus.COMPROMISED.wireValue());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT invalidation_reason FROM iam_session.oauth_authorizations WHERE id = ?",
                String.class,
                initial.getId()
        )).isEqualTo("refresh_token_reused");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reuse_detected_at IS NOT NULL FROM iam_session.oauth_refresh_tokens WHERE token_digest = ?",
                Boolean.class,
                TokenDigest.sha256(firstRefreshToken)
        )).isTrue();
        assertThat(authorizations.findByToken(secondRefreshToken, OAuth2TokenType.REFRESH_TOKEN)).isNull();
    }

    @Test
    void concurrentRefreshSerializesRotationAndTreatsTheSecondUseAsReplay() throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        UUID accountId = identities.issueSignInGrant(
                bytes(3),
                bytes(4),
                "vk-platform-web",
                now.plus(Duration.ofMinutes(5)),
                now,
                "request-concurrent-account"
        ).accountId();
        UUID sessionId = UUID.randomUUID();
        sessions.create(
                UserSession.create(sessionId, accountId, CLIENT_ID, now, now.plus(Duration.ofDays(30))),
                "request-concurrent-session"
        );
        RegisteredClient client = registeredClient();
        clients.save(client);
        String refreshToken = "concurrent-refresh-token-that-must-not-be-stored";
        authorizations.save(authorization(
                client,
                accountId,
                sessionId,
                "concurrent-access-token",
                refreshToken,
                now
        ));

        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() -> transaction.execute(status -> {
                OAuth2Authorization loaded = authorizations.findByToken(
                        refreshToken,
                        OAuth2TokenType.REFRESH_TOKEN
                );
                firstLocked.countDown();
                await(secondAttempted);
                authorizations.save(OAuth2Authorization.from(loaded)
                        .accessToken(new OAuth2AccessToken(
                                OAuth2AccessToken.TokenType.BEARER,
                                "first-concurrent-rotated-access-token",
                                now.plusSeconds(1),
                                now.plus(Duration.ofMinutes(5)),
                                Set.of("api.access")
                        ))
                        .refreshToken(new OAuth2RefreshToken(
                                "first-concurrent-rotated-refresh-token",
                                now.plusSeconds(1),
                                now.plus(Duration.ofDays(30))
                        ))
                        .build());
                return true;
            }));
            Future<Boolean> second = executor.submit(() -> {
                assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue();
                return transaction.execute(status -> {
                    secondAttempted.countDown();
                    return authorizations.findByToken(
                            refreshToken,
                            OAuth2TokenType.REFRESH_TOKEN
                    ) != null;
                });
            });

            assertThat(List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            )).containsExactly(true, false);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                sessionId
        )).isEqualTo(SessionStatus.COMPROMISED.wireValue());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.oauth_refresh_tokens WHERE authorization_id = (" +
                        "SELECT id FROM iam_session.oauth_authorizations WHERE session_id = ?)",
                Integer.class,
                sessionId
        )).isEqualTo(2);
    }

    private static RegisteredClient registeredClient() {
        return RegisteredClient.withId(CLIENT_REGISTRATION_ID)
                .clientId(CLIENT_ID)
                .clientIdIssuedAt(Instant.parse("2026-10-11T00:00:00Z"))
                .clientSecret("{noop}test-client-secret")
                .clientName("VK Platform test BFF")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://web.example.test/api/auth/callback")
                .scope("api.access")
                .clientSettings(ClientSettings.builder().requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder().reuseRefreshTokens(false).build())
                .build();
    }

    private static OAuth2Authorization authorization(
            RegisteredClient client,
            UUID accountId,
            UUID sessionId,
            String accessToken,
            String refreshToken,
            Instant now
    ) {
        UsernamePasswordAuthenticationToken principal = UsernamePasswordAuthenticationToken.authenticated(
                accountId.toString(),
                null,
                Set.of()
        );
        Map<String, String> details = new HashMap<>();
        details.put("session_id", sessionId.toString());
        details.put("amr", "email_otp");
        principal.setDetails(details);
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://iam.example.test/oauth2/authorize")
                .clientId(CLIENT_ID)
                .redirectUri("https://web.example.test/api/auth/callback")
                .scopes(Set.of("api.access"))
                .state("state-that-must-not-be-stored")
                .additionalParameters(parameters -> parameters.put(
                        "code_challenge",
                        "pkce-code-challenge"
                ))
                .build();
        return OAuth2Authorization.withRegisteredClient(client)
                .id(UUID.randomUUID().toString())
                .principalName(accountId.toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizedScopes(Set.of("api.access"))
                .attribute(Principal.class.getName(), principal)
                .attribute(OAuth2AuthorizationRequest.class.getName(), authorizationRequest)
                .attribute(OAuth2ParameterNames.STATE, "state-that-must-not-be-stored")
                .accessToken(new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER,
                        accessToken,
                        now,
                        now.plus(Duration.ofMinutes(5)),
                        Set.of("api.access")
                ))
                .refreshToken(new OAuth2RefreshToken(
                        refreshToken,
                        now,
                        now.plus(Duration.ofDays(30))
                ))
                .build();
    }

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent refresh did not start in time");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent refresh was interrupted", exception);
        }
    }
}
