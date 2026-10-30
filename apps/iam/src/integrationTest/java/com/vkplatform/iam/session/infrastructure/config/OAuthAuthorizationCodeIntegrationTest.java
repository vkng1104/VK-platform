package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.api.OAuthClientOperations;
import com.vkplatform.iam.session.api.OAuthClientRegistrationOperations;
import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "iam.session.enabled=true",
        "iam.email.provider=disabled",
        "iam.email.cleanup-enabled=false",
        "iam.oauth.enabled=true",
        "iam.session.bff-client-id=vk-platform-bff",
        "iam.session.bff-origin=https://web.example.test",
        "iam.session.bff-client-secret=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "iam.oauth.authorization.issuer=https://iam.example.test",
        "iam.oauth.authorization.audience=vk-platform-api",
        "iam.oauth.authorization.login-uri=https://web.example.test/sign-in",
        "iam.oauth.authorization.bff-redirect-uri=https://web.example.test/api/auth/callback",
        "iam.oauth.clients.service-client-id=vk-platform-service",
        "iam.oauth.clients.service-bootstrap-secret=ssssssssssssssssssssssssssssssss",
        "iam.oauth.clients.service-audience=vk-platform-services"
})
@AutoConfigureMockMvc
@Import(OAuthAuthorizationCodeIntegrationTest.TestBeans.class)
class OAuthAuthorizationCodeIntegrationTest {
    private static final String BFF_CLIENT_ID = "vk-platform-bff";
    private static final String BFF_SECRET = "b".repeat(32);
    private static final String SERVICE_CLIENT_ID = "vk-platform-service";
    private static final String SERVICE_SECRET = "s".repeat(32);
    private static final String BFF_REDIRECT = "https://web.example.test/api/auth/callback";
    private static final String VERIFIER = "v".repeat(64);
    private static final String GRANT = "A".repeat(43);
    private static final UUID ACCOUNT_ID = UUID.fromString("8c359d35-f8d7-4e2f-a3f5-a14242fe621f");
    private static final KeyPair SIGNING_PAIR = signingPair();
    private static final byte[] PROTECTION_KEY = protectionKey();

    @DynamicPropertySource
    static void signingProperties(DynamicPropertyRegistry properties) {
        properties.add("iam.oauth.signing-key-id", () -> "oauth-integration-key");
        properties.add("iam.oauth.signing-private-key-pkcs8-base64", () -> encoded(
                SIGNING_PAIR.getPrivate().getEncoded()
        ));
        properties.add("iam.oauth.signing-public-key-x509-base64", () -> encoded(
                SIGNING_PAIR.getPublic().getEncoded()
        ));
        properties.add("iam.signing-keys.protection-version", () -> "integration-v1");
        properties.add("iam.signing-keys.protection-keys", () ->
                "integration-v1=" + encoded(PROTECTION_KEY));
        properties.add("iam.signing-keys.retirement-enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordlessIdentityOperations identities;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SigningKeyOperations signingKeys;

    @Autowired
    private OAuthClientRegistrationOperations clientRegistrations;

    @Autowired
    private OAuthClientOperations clientCredentials;

    @BeforeEach
    void resetDatabaseAndIdentity() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_client_events");
        jdbcTemplate.update("DELETE FROM iam_session.signing_key_events");
        jdbcTemplate.update("DELETE FROM iam_session.operator_operations");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_clients");
        jdbcTemplate.update("DELETE FROM iam_session.signing_keys");
        jdbcTemplate.update("DELETE FROM iam_identity.security_events");
        jdbcTemplate.update("DELETE FROM iam_identity.verification_grants");
        jdbcTemplate.update("DELETE FROM iam_identity.login_identities");
        jdbcTemplate.update("DELETE FROM iam_identity.accounts");
        Instant now = Instant.now().minusSeconds(1);
        jdbcTemplate.update("""
                INSERT INTO iam_identity.accounts (
                    id, status, token_not_before, version, created_at, updated_at
                ) VALUES (?, 'active', ?, 0, ?, ?)
                """,
                ACCOUNT_ID,
                timestamp(now),
                timestamp(now),
                timestamp(now)
        );
        reset(identities);
        when(identities.consumeSignInGrant(any())).thenReturn(new AuthenticatedAccount(ACCOUNT_ID, now));
        when(identities.activeAccount(ACCOUNT_ID)).thenReturn(new AuthenticatedAccount(ACCOUNT_ID, now));
        signingKeys.bootstrap(new OperatorContext(
                "oauth-integration-test",
                "bootstrap-oauth-integration"
        ));
        clientRegistrations.reconcile(new OperatorContext(
                "oauth-integration-test",
                "reconcile-oauth-integration"
        ));
    }

    @Test
    void issuesSessionlessServiceTokensWithOnlyTheConfiguredAudienceAndScopes() throws Exception {
        MvcResult result = serviceToken(SERVICE_SECRET, serviceScopes())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        Jwt accessToken = NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_PAIR.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
                .decode(response.get("access_token").asText());
        assertThat(accessToken.getSubject()).isEqualTo(SERVICE_CLIENT_ID);
        assertThat(accessToken.getClaimAsString("client_id")).isEqualTo(SERVICE_CLIENT_ID);
        assertThat(accessToken.getAudience()).containsExactly("vk-platform-services");
        assertThat(accessToken.getClaimAsStringList("scope"))
                .containsExactlyInAnyOrder("iam.authorization.check", "iam.relationship.write");
        assertThat(accessToken.getClaims()).doesNotContainKeys("sid", "amr");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT authorization_grant_type
                FROM iam_session.oauth_authorizations
                """, String.class)).isEqualTo("client_credentials");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_session.oauth_authorizations
                WHERE session_id IS NULL
                  AND access_token_digest IS NOT NULL
                  AND refresh_token_digest IS NULL
                """, Integer.class)).isOne();

        serviceToken(SERVICE_SECRET, "api.access")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
        serviceToken("x".repeat(32), serviceScopes())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"));
    }

    @Test
    void rotatesAClientSecretIdempotentlyWithImmediateCutover() throws Exception {
        IssuedSession browserSession = issueSession();
        String newSecret = "n".repeat(32);
        Instant expiresAt = Instant.now().plusSeconds(3600);
        OperatorContext operator = new OperatorContext(
                "oauth-integration-test",
                "rotate-service-secret"
        );
        RotateOAuthClientSecret command = new RotateOAuthClientSecret(
                SERVICE_CLIENT_ID,
                newSecret,
                expiresAt,
                operator
        );

        assertThat(clientCredentials.rotateSecret(command))
                .isEqualTo(clientCredentials.rotateSecret(command));
        serviceToken(SERVICE_SECRET, serviceScopes())
                .andExpect(status().isUnauthorized());
        serviceToken(newSecret, serviceScopes())
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("""
                SELECT client_secret
                FROM iam_session.oauth_clients
                WHERE client_id = ?
                """, String.class, SERVICE_CLIENT_ID))
                .doesNotContain(newSecret)
                .doesNotContain(SERVICE_SECRET);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_session.oauth_client_events
                WHERE event_type = 'credential_rotated'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                browserSession.sessionId()
        )).isEqualTo("active");

        assertThatThrownBy(() -> clientCredentials.rotateSecret(new RotateOAuthClientSecret(
                SERVICE_CLIENT_ID,
                "z".repeat(32),
                expiresAt,
                operator
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operator operation ID is already in use");
    }

    @Test
    void exchangesAnAuthorizationCodeWithPkceForAConstrainedSignedAccessToken() throws Exception {
        MockHttpSession browser = exchangeGrant();
        String code = authorizationCode(browser, BFF_REDIRECT, true);

        MvcResult tokenResult = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(BFF_CLIENT_ID, BFF_SECRET))
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", BFF_REDIRECT)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").isNumber())
                .andExpect(jsonPath("$.refresh_token").isString())
                .andReturn();

        JsonNode response = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        Jwt accessToken = NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_PAIR.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
                .decode(response.get("access_token").asText());
        assertThat(accessToken.getIssuer().toString()).isEqualTo("https://iam.example.test");
        assertThat(accessToken.getSubject()).isEqualTo(ACCOUNT_ID.toString());
        assertThat(accessToken.getAudience()).containsExactly("vk-platform-api");
        assertThat(accessToken.getClaimAsString("client_id")).isEqualTo(BFF_CLIENT_ID);
        assertThat(accessToken.getClaimAsString("sid")).isNotBlank();
        assertThat(accessToken.getClaimAsStringList("amr")).containsExactly("email_otp");
        assertThat(accessToken.getHeaders().get("kid")).isEqualTo("oauth-integration-key");
        assertThat(accessToken.getClaims()).doesNotContainKeys("email", "role", "permissions");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT OCTET_LENGTH(authorization_code_digest)
                FROM iam_session.oauth_authorizations
                """, Integer.class)).isEqualTo(32);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT attributes_json
                FROM iam_session.oauth_authorizations
                """, String.class)).doesNotContain(code);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT OCTET_LENGTH(access_token_digest)
                FROM iam_session.oauth_authorizations
                """, Integer.class)).isEqualTo(32);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT OCTET_LENGTH(refresh_token_digest)
                FROM iam_session.oauth_authorizations
                """, Integer.class)).isEqualTo(32);

        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(BFF_CLIENT_ID, BFF_SECRET))
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", BFF_REDIRECT)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void rotatesRefreshTokensOnceAndCompromisesTheSessionOnReplay() throws Exception {
        MockHttpSession browser = exchangeGrant();
        String code = authorizationCode(browser, BFF_REDIRECT, true);
        MvcResult initial = exchangeCode(code);
        JsonNode initialTokens = objectMapper.readTree(initial.getResponse().getContentAsString());
        String firstRefresh = initialTokens.get("refresh_token").asText();

        MvcResult refreshed = refresh(firstRefresh)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refresh_token").isString())
                .andReturn();
        String rotatedRefresh = objectMapper.readTree(
                refreshed.getResponse().getContentAsString()
        ).get("refresh_token").asText();

        assertThat(rotatedRefresh).isNotEqualTo(firstRefresh);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.oauth_refresh_tokens",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_session.oauth_refresh_tokens
                WHERE consumed_at IS NOT NULL AND replacement_id IS NOT NULL
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_identity.security_events
                WHERE event_type = 'refresh_token_rotated'
                """, Integer.class)).isOne();

        refresh(firstRefresh)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE account_id = ?",
                String.class,
                ACCOUNT_ID
        )).isEqualTo("compromised");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoke_reason FROM iam_session.sessions WHERE account_id = ?",
                String.class,
                ACCOUNT_ID
        )).isEqualTo("refresh_token_reused");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT invalidation_reason FROM iam_session.oauth_authorizations",
                String.class
        )).isEqualTo("refresh_token_reused");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_session.oauth_refresh_tokens
                WHERE reuse_detected_at IS NOT NULL
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_identity.security_events
                WHERE event_type = 'refresh_token_reused'
                """, Integer.class)).isOne();

        refresh(rotatedRefresh)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void rejectsRedirectAndPkceAttackPathsAndKeepsThePublicKeyPrivateFieldsHidden() throws Exception {
        MockHttpSession browser = exchangeGrant();

        mockMvc.perform(authorizationRequest(browser, "https://attacker.example/callback", true))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authorizationRequest(browser, BFF_REDIRECT, false))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith(BFF_REDIRECT)
                        .contains("error=invalid_request"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.oauth_authorizations",
                Integer.class
        )).isZero();

        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(header().string("Cache-Control", containsString("public")))
                .andExpect(header().string("Cache-Control", containsString("must-revalidate")))
                .andExpect(header().doesNotExist("Pragma"))
                .andExpect(header().doesNotExist("Expires"))
                .andExpect(jsonPath("$.keys[0].kid").value("oauth-integration-key"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void publishesRetiringKeysForOverlapAndRemovesCompromisedKeysImmediately() throws Exception {
        IssuedSession issued = issueSession();

        signingKeys.rotate(new OperatorContext("oauth-integration-test", "rotate-overlap"));

        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=300")))
                .andExpect(jsonPath("$.keys.length()").value(2));
        mockMvc.perform(get("/api/v1/authentication/session")
                        .header(HttpHeaders.AUTHORIZATION, bearer(issued.accessToken())))
                .andExpect(status().isOk());

        signingKeys.compromise(
                "oauth-integration-key",
                new OperatorContext("oauth-integration-test", "compromise-retiring")
        );

        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[?(@.kid == 'oauth-integration-key')]").isEmpty());
        mockMvc.perform(get("/api/v1/authentication/session")
                        .header(HttpHeaders.AUTHORIZATION, bearer(issued.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAFrameworkLoginAfterItsDatabaseSessionExpires() throws Exception {
        MockHttpSession browser = exchangeGrant();
        Instant now = Instant.now();
        jdbcTemplate.update("""
                UPDATE iam_session.sessions
                SET created_at = ?, expires_at = ?
                WHERE account_id = ?
                """,
                timestamp(now.minusSeconds(7200)),
                timestamp(now.minusSeconds(3600)),
                ACCOUNT_ID
        );

        MvcResult result = mockMvc.perform(authorizationRequest(browser, BFF_REDIRECT, true))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("https://web.example.test/sign-in");
        assertThat(browser.isInvalid()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.oauth_authorizations",
                Integer.class
        )).isZero();
    }

    @Test
    void managesOwnedSessionsAndInvalidatesTheirAuthorizationsAtomically() throws Exception {
        IssuedSession current = issueSession();
        IssuedSession other = issueSession();

        mockMvc.perform(get("/api/v1/authentication/session")
                        .header(HttpHeaders.AUTHORIZATION, bearer(current.accessToken())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.session.id").value(current.sessionId().toString()))
                .andExpect(jsonPath("$.session.client_id").value(BFF_CLIENT_ID))
                .andExpect(jsonPath("$.session.status").value("active"));

        mockMvc.perform(get("/api/v1/authentication/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(current.accessToken()))
                        .queryParam("page", "0")
                        .queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.sessions.length()").value(2))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.total_elements").value(2))
                .andExpect(jsonPath("$.page.total_pages").value(1));

        mockMvc.perform(delete("/api/v1/authentication/sessions/{session_id}", other.sessionId())
                        .session(current.browser())
                        .header(HttpHeaders.AUTHORIZATION, bearer(current.accessToken())))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(current.browser().isInvalid()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                other.sessionId()
        )).isEqualTo("revoked");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoke_reason FROM iam_session.sessions WHERE id = ?",
                String.class,
                other.sessionId()
        )).isEqualTo("user_request");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT invalidation_reason FROM iam_session.oauth_authorizations WHERE session_id = ?",
                String.class,
                other.sessionId()
        )).isEqualTo("user_request");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE id = ?",
                String.class,
                current.sessionId()
        )).isEqualTo("active");

        mockMvc.perform(delete("/api/v1/authentication/sessions/{session_id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(current.accessToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SESSION_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/authentication/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(current.accessToken()))
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SESSION_REQUEST"));

        mockMvc.perform(delete("/api/v1/authentication/sessions")
                        .session(current.browser())
                        .header(HttpHeaders.AUTHORIZATION, bearer(current.accessToken())))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("VK_IAM_SESSION=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        assertThat(current.browser().isInvalid()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_session.sessions
                WHERE account_id = ? AND status = 'active'
                """, Integer.class, ACCOUNT_ID)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_session.oauth_authorizations
                WHERE session_id = ? AND invalidation_reason = 'all_sessions_revoked'
                """, Integer.class, current.sessionId())).isOne();
    }

    @Test
    void logsOutIdempotentlyAndClearsTheFrameworkSessionCookie() throws Exception {
        IssuedSession issued = issueSession();

        mockMvc.perform(post("/api/v1/authentication/logout")
                        .session(issued.browser())
                        .header(HttpHeaders.AUTHORIZATION, bearer(issued.accessToken())))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("VK_IAM_SESSION=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        assertThat(issued.browser().isInvalid()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoke_reason FROM iam_session.sessions WHERE id = ?",
                String.class,
                issued.sessionId()
        )).isEqualTo("logout");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT invalidation_reason FROM iam_session.oauth_authorizations WHERE session_id = ?",
                String.class,
                issued.sessionId()
        )).isEqualTo("logout");

        mockMvc.perform(post("/api/v1/authentication/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(issued.accessToken())))
                .andExpect(status().isNoContent());
    }

    private MockHttpSession exchangeGrant() throws Exception {
        MvcResult exchange = mockMvc.perform(post("/api/v1/authentication/grants/exchange")
                        .with(httpBasic(BFF_CLIENT_ID, BFF_SECRET))
                        .header("Origin", "https://web.example.test")
                        .contentType("application/json")
                        .content("""
                                {"grant":"%s","audience":"vk-platform-web"}
                                """.formatted(GRANT)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(exchange.getRequest().getSession(false)).isInstanceOf(MockHttpSession.class);
        return (MockHttpSession) exchange.getRequest().getSession(false);
    }

    private IssuedSession issueSession() throws Exception {
        MockHttpSession browser = exchangeGrant();
        String code = authorizationCode(browser, BFF_REDIRECT, true);
        JsonNode tokens = objectMapper.readTree(
                exchangeCode(code).getResponse().getContentAsString()
        );
        String accessToken = tokens.get("access_token").asText();
        Jwt decoded = NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_PAIR.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build()
                .decode(accessToken);
        return new IssuedSession(
                browser,
                accessToken,
                UUID.fromString(decoded.getClaimAsString("sid"))
        );
    }

    private String authorizationCode(
            MockHttpSession browser,
            String redirectUri,
            boolean includePkce
    ) throws Exception {
        MvcResult result = mockMvc.perform(authorizationRequest(browser, redirectUri, includePkce))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(redirectUri);
        String code = UriComponentsBuilder.fromUri(URI.create(location))
                .build()
                .getQueryParams()
                .getFirst("code");
        assertThat(code).isNotBlank();
        return code;
    }

    private MvcResult exchangeCode(String code) throws Exception {
        return mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(BFF_CLIENT_ID, BFF_SECRET))
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", BFF_REDIRECT)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken)
            throws Exception {
        return mockMvc.perform(post("/oauth2/token")
                .with(httpBasic(BFF_CLIENT_ID, BFF_SECRET))
                .contentType("application/x-www-form-urlencoded")
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken));
    }

    private org.springframework.test.web.servlet.ResultActions serviceToken(
            String secret,
            String scopes
    ) throws Exception {
        return mockMvc.perform(post("/oauth2/token")
                .with(httpBasic(SERVICE_CLIENT_ID, secret))
                .contentType("application/x-www-form-urlencoded")
                .param("grant_type", "client_credentials")
                .param("scope", scopes));
    }

    private static String serviceScopes() {
        return "iam.authorization.check iam.relationship.write";
    }

    private static MockHttpServletRequestBuilder authorizationRequest(
            MockHttpSession browser,
            String redirectUri,
            boolean includePkce
    ) {
        MockHttpServletRequestBuilder request = get("/oauth2/authorize")
                .session(browser)
                .queryParam("response_type", "code")
                .queryParam("client_id", BFF_CLIENT_ID)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "api.access")
                .queryParam("state", "opaque-browser-state");
        if (includePkce) {
            request.queryParam("code_challenge", codeChallenge(VERIFIER))
                    .queryParam("code_challenge_method", "S256");
        }
        return request;
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static KeyPair signingPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static byte[] protectionKey() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        return key;
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record IssuedSession(
            MockHttpSession browser,
            String accessToken,
            UUID sessionId
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        PasswordlessIdentityOperations passwordlessIdentityOperations() {
            return mock(PasswordlessIdentityOperations.class);
        }
    }
}
