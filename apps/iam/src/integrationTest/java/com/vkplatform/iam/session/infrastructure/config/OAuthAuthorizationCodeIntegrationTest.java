package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
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
        "iam.oauth.authorization.bff-redirect-uri=https://web.example.test/api/auth/callback"
})
@AutoConfigureMockMvc
@Import(OAuthAuthorizationCodeIntegrationTest.TestBeans.class)
class OAuthAuthorizationCodeIntegrationTest {
    private static final String BFF_CLIENT_ID = "vk-platform-bff";
    private static final String BFF_SECRET = "b".repeat(32);
    private static final String BFF_REDIRECT = "https://web.example.test/api/auth/callback";
    private static final String VERIFIER = "v".repeat(64);
    private static final String GRANT = "A".repeat(43);
    private static final UUID ACCOUNT_ID = UUID.fromString("8c359d35-f8d7-4e2f-a3f5-a14242fe621f");
    private static final KeyPair SIGNING_PAIR = signingPair();

    @DynamicPropertySource
    static void signingProperties(DynamicPropertyRegistry properties) {
        properties.add("iam.oauth.signing-key-id", () -> "oauth-integration-key");
        properties.add("iam.oauth.signing-private-key-pkcs8-base64", () -> encoded(
                SIGNING_PAIR.getPrivate().getEncoded()
        ));
        properties.add("iam.oauth.signing-public-key-x509-base64", () -> encoded(
                SIGNING_PAIR.getPublic().getEncoded()
        ));
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
    private DataSource dataSource;

    @BeforeEach
    void resetDatabaseAndIdentity() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
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

        refresh(rotatedRefresh)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void handlesOverlappingRefreshesOfOneTokenAsReuseWithoutAServerError() throws Exception {
        MockHttpSession browser = exchangeGrant();
        String code = authorizationCode(browser, BFF_REDIRECT, true);
        String firstRefresh = objectMapper.readTree(
                exchangeCode(code).getResponse().getContentAsString()
        ).get("refresh_token").asText();

        List<MvcResult> results = whileTheSessionRowIsLocked(
                () -> refresh(firstRefresh).andReturn(),
                () -> refresh(firstRefresh).andReturn()
        );

        assertThat(results)
                .extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 400);
        assertThat(errorOf(withStatus(results, 400))).isEqualTo("invalid_grant");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.oauth_refresh_tokens",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_session.oauth_refresh_tokens
                WHERE consumed_at IS NOT NULL AND replacement_id IS NOT NULL
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE account_id = ?",
                String.class,
                ACCOUNT_ID
        )).isEqualTo("compromised");
    }

    @Test
    void handlesOverlappingCodeExchangesAsReplayWithoutAServerError() throws Exception {
        MockHttpSession browser = exchangeGrant();
        String code = authorizationCode(browser, BFF_REDIRECT, true);

        List<MvcResult> results = whileTheSessionRowIsLocked(
                () -> codeExchange(code).andReturn(),
                () -> codeExchange(code).andReturn()
        );

        assertThat(results)
                .extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 400);
        assertThat(errorOf(withStatus(results, 400))).isEqualTo("invalid_grant");
        String issuedRefresh = objectMapper.readTree(
                withStatus(results, 200).getResponse().getContentAsString()
        ).get("refresh_token").asText();
        refresh(issuedRefresh)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE account_id = ?",
                String.class,
                ACCOUNT_ID
        )).isEqualTo("active");
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
        return codeExchange(code)
                .andExpect(status().isOk())
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions codeExchange(String code)
            throws Exception {
        return mockMvc.perform(post("/oauth2/token")
                .with(httpBasic(BFF_CLIENT_ID, BFF_SECRET))
                .contentType("application/x-www-form-urlencoded")
                .param("grant_type", "authorization_code")
                .param("code", code)
                .param("redirect_uri", BFF_REDIRECT)
                .param("code_verifier", VERIFIER));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken)
            throws Exception {
        return mockMvc.perform(post("/oauth2/token")
                .with(httpBasic(BFF_CLIENT_ID, BFF_SECRET))
                .contentType("application/x-www-form-urlencoded")
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken));
    }

    /**
     * Holds the session row lock until both requests are waiting for it, so each request has
     * already performed its unlocked lookups before either one can proceed.
     */
    private List<MvcResult> whileTheSessionRowIsLocked(
            Callable<MvcResult> first,
            Callable<MvcResult> second
    ) throws Exception {
        UUID sessionId = jdbcTemplate.queryForObject(
                "SELECT id FROM iam_session.sessions WHERE account_id = ?",
                UUID.class,
                ACCOUNT_ID
        );
        try (ExecutorService executor = Executors.newFixedThreadPool(2);
             Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT id FROM iam_session.sessions WHERE id = ? FOR UPDATE"
            )) {
                lock.setObject(1, sessionId);
                lock.executeQuery().close();
            }
            Future<MvcResult> firstResult = executor.submit(first);
            Future<MvcResult> secondResult = executor.submit(second);
            awaitWaitingLockRequests(2);
            blocker.commit();
            return List.of(
                    firstResult.get(30, TimeUnit.SECONDS),
                    secondResult.get(30, TimeUnit.SECONDS)
            );
        }
    }

    private void awaitWaitingLockRequests(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE NOT granted",
                Integer.class
        ) < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("token requests did not wait for the session row lock");
            }
            Thread.sleep(20);
        }
    }

    private static MvcResult withStatus(List<MvcResult> results, int status) {
        return results.stream()
                .filter(result -> result.getResponse().getStatus() == status)
                .findFirst()
                .orElseThrow();
    }

    private String errorOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("error").asText();
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

    private static OffsetDateTime timestamp(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
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
