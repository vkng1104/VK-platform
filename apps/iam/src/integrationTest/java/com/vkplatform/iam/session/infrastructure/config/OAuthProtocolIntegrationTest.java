package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.identity.infrastructure.persistence.repository.JpaPasswordlessIdentityRepository;
import com.vkplatform.iam.session.infrastructure.persistence.repository.JpaSigningKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "iam.session.enabled=true",
        "iam.email.provider=disabled",
        "iam.email.cleanup-enabled=false",
        "iam.session.issuer=https://iam.example.test",
        "iam.session.bff-client-id=vk-platform-bff",
        "iam.session.bff-client-secret=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "iam.session.bff-redirect-uri=https://web.example.test/api/auth/callback",
        "iam.session.service-client-id=vk-platform-api",
        "iam.session.service-client-secret=ssssssssssssssssssssssssssssssss"
})
@AutoConfigureMockMvc
@Import(OAuthProtocolIntegrationTest.TestBeans.class)
class OAuthProtocolIntegrationTest {
    private static final String BFF_CLIENT_ID = "vk-platform-bff";
    private static final String BFF_SECRET = "b".repeat(32);
    private static final String BFF_REDIRECT = "https://web.example.test/api/auth/callback";
    private static final String SERVICE_CLIENT_ID = "vk-platform-api";
    private static final String SERVICE_SECRET = "s".repeat(32);
    private static final String VERIFIER = "v".repeat(64);
    private static final KeyPair SIGNING_PAIR = signingPair();

    @DynamicPropertySource
    static void signingProperties(DynamicPropertyRegistry properties) {
        byte[] encryptionKey = new byte[32];
        Arrays.fill(encryptionKey, (byte) 7);
        properties.add("iam.session.signing-key-id", () -> "protocol-test-key");
        properties.add("iam.session.signing-private-key-pkcs8-base64", () ->
                Base64.getEncoder().encodeToString(SIGNING_PAIR.getPrivate().getEncoded()));
        properties.add("iam.session.signing-public-key-x509-base64", () ->
                Base64.getEncoder().encodeToString(SIGNING_PAIR.getPublic().getEncoded()));
        properties.add("iam.session.signing-key-encryption-key-base64", () ->
                Base64.getEncoder().encodeToString(encryptionKey));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JpaPasswordlessIdentityRepository identityRepository;

    @Autowired
    private PasswordlessIdentityOperations identities;

    @Autowired
    private JpaSigningKeyStore signingKeys;

    @Autowired
    private List<ApplicationRunner> startupRunners;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID accountId;

    @BeforeEach
    void resetDatabaseAndProtocolState() throws Exception {
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

        for (ApplicationRunner runner : startupRunners) {
            runner.run(new DefaultApplicationArguments(new String[0]));
        }
        signingKeys.bootstrap();
        reset(identities);
        Instant now = Instant.now().minusSeconds(1);
        accountId = identityRepository.issueSignInGrant(
                bytes(5),
                bytes(6),
                "vk-platform-web",
                now.plus(Duration.ofMinutes(5)),
                now,
                "request-protocol-account"
        ).accountId();
        when(identities.consumeSignInGrant(any())).thenReturn(new AuthenticatedAccount(accountId, now));
        when(identities.activeAccount(accountId)).thenReturn(new AuthenticatedAccount(accountId, now));
    }

    @Test
    void completesPasswordlessAuthorizationCodePkceAndIssuesAConstrainedUserToken() throws Exception {
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
                .andExpect(jsonPath("$.expires_in").value(org.hamcrest.Matchers.lessThanOrEqualTo(300)))
                .andExpect(jsonPath("$.refresh_token").isString())
                .andReturn();

        JsonNode tokenResponse = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        Jwt accessToken = jwtDecoder.decode(tokenResponse.get("access_token").asText());
        assertThat(accessToken.getIssuer().toString()).isEqualTo("https://iam.example.test");
        assertThat(accessToken.getSubject()).isEqualTo(accountId.toString());
        assertThat(accessToken.getAudience()).containsExactly("vk-platform-api");
        assertThat(accessToken.getClaimAsString("sid")).isNotBlank();
        assertThat(accessToken.getClaimAsStringList("amr")).containsExactly("email_otp");
        assertThat(accessToken.getHeaders().get("kid")).isEqualTo("protocol-test-key");
        assertThat(accessToken.getClaims()).doesNotContainKeys("email", "role", "permissions");
        assertThat(Duration.between(accessToken.getIssuedAt(), accessToken.getExpiresAt()))
                .isLessThanOrEqualTo(Duration.ofMinutes(5));

        String firstRefreshToken = tokenResponse.get("refresh_token").asText();
        MvcResult refreshResult = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(BFF_CLIENT_ID, BFF_SECRET))
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", firstRefreshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refresh_token").isString())
                .andReturn();
        String rotatedRefreshToken = objectMapper.readTree(
                refreshResult.getResponse().getContentAsString()
        ).get("refresh_token").asText();
        assertThat(rotatedRefreshToken).isNotEqualTo(firstRefreshToken);

        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(BFF_CLIENT_ID, BFF_SECRET))
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", firstRefreshToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM iam_session.sessions WHERE account_id = ?",
                String.class,
                accountId
        )).isEqualTo("compromised");

        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(BFF_CLIENT_ID, BFF_SECRET))
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "refresh_token")
                        .param("refresh_token", rotatedRefreshToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void rejectsRedirectAndPkceAttackPathsWithoutIssuingAnAuthorizationCode() throws Exception {
        MockHttpSession browser = exchangeGrant();

        mockMvc.perform(authorizationRequest(browser, "https://attacker.example/callback", true))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authorizationRequest(browser, BFF_REDIRECT, false))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                        .startsWith(BFF_REDIRECT)
                        .contains("error=invalid_request")
                        .contains("code_challenge"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.oauth_authorizations",
                Integer.class
        )).isZero();
    }

    @Test
    void publishesDiscoveryAndJwksAndRejectsServiceScopeEscalation() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("https://iam.example.test"))
                .andExpect(jsonPath("$.jwks_uri").value("https://iam.example.test/oauth2/jwks"));
        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value("protocol-test-key"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());

        MvcResult serviceResult = mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(SERVICE_CLIENT_ID, SERVICE_SECRET))
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "client_credentials")
                        .param("scope", "iam.authorization.check"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode serviceResponse = objectMapper.readTree(serviceResult.getResponse().getContentAsString());
        JsonNode serviceClaims = jwtPayload(serviceResponse.get("access_token").asText());
        assertThat(serviceClaims.get("sub").asText()).isEqualTo(SERVICE_CLIENT_ID);
        assertThat(serviceClaims.get("aud").asText()).isEqualTo("vk-platform-iam");
        assertThat(serviceClaims.has("sid")).isFalse();
        assertThat(serviceClaims.has("permissions")).isFalse();

        mockMvc.perform(post("/oauth2/token")
                        .with(httpBasic(SERVICE_CLIENT_ID, SERVICE_SECRET))
                        .contentType("application/x-www-form-urlencoded")
                        .param("grant_type", "client_credentials")
                        .param("scope", "iam.authorization.check forbidden.scope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    private MockHttpSession exchangeGrant() throws Exception {
        MvcResult exchange = mockMvc.perform(post("/api/v1/authentication/grants/exchange")
                        .contentType("application/json")
                        .content("""
                                {"grant":"%s","audience":"vk-platform-web"}
                                """.formatted("A".repeat(43))))
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

    private JsonNode jwtPayload(String token) throws Exception {
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        return objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
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

    private static byte[] bytes(int value) {
        byte[] result = new byte[32];
        Arrays.fill(result, (byte) value);
        return result;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        PasswordlessIdentityOperations testPasswordlessIdentityOperations() {
            return mock(PasswordlessIdentityOperations.class);
        }
    }
}
