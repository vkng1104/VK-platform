package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.session.api.OAuthClientOperations;
import com.vkplatform.iam.session.api.OAuthClientReadiness;
import com.vkplatform.iam.session.api.OAuthClientRegistrationOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import com.vkplatform.iam.session.api.result.OAuthClientSecretRotation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = {
        "iam.session.enabled=true",
        "iam.email.provider=disabled",
        "iam.email.cleanup-enabled=false",
        "iam.oauth.enabled=true",
        "iam.session.bff-client-id=vk-platform-bff",
        "iam.session.bff-client-secret=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "iam.session.bff-origin=https://web.example.test",
        "iam.oauth.authorization.issuer=https://iam.example.test",
        "iam.oauth.authorization.audience=vk-platform-api",
        "iam.oauth.authorization.login-uri=https://web.example.test/sign-in",
        "iam.oauth.authorization.bff-redirect-uri=https://web.example.test/api/auth/callback",
        "iam.oauth.clients.service-client-id=vk-platform-service",
        "iam.oauth.clients.service-bootstrap-secret=ssssssssssssssssssssssssssssssss",
        "iam.oauth.clients.service-audience=vk-platform-services",
        "iam.signing-keys.protection-version=client-test-v1",
        "iam.signing-keys.retirement-enabled=false"
})
@Import(JpaOAuthClientLifecycleIntegrationTest.TestClockConfiguration.class)
class JpaOAuthClientLifecycleIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-30T04:00:00Z");
    private static final String BFF_CLIENT_ID = "vk-platform-bff";
    private static final String BFF_SECRET = "b".repeat(32);
    private static final String SERVICE_CLIENT_ID = "vk-platform-service";
    private static final String SERVICE_SECRET = "s".repeat(32);
    private static final KeyPair SIGNING_PAIR = signingPair();
    private static final byte[] PROTECTION_KEY = protectionKey();

    @DynamicPropertySource
    static void signingProperties(DynamicPropertyRegistry properties) {
        properties.add("iam.oauth.signing-key-id", () -> "oauth-client-test-key");
        properties.add("iam.oauth.signing-private-key-pkcs8-base64", () -> encoded(
                SIGNING_PAIR.getPrivate().getEncoded()
        ));
        properties.add("iam.oauth.signing-public-key-x509-base64", () -> encoded(
                SIGNING_PAIR.getPublic().getEncoded()
        ));
        properties.add("iam.signing-keys.protection-keys", () ->
                "client-test-v1=" + encoded(PROTECTION_KEY));
    }

    @Autowired
    private OAuthClientRegistrationOperations registrations;

    @Autowired
    private OAuthClientOperations credentials;

    @Autowired
    private OAuthClientReadiness readiness;

    @Autowired
    private JpaRegisteredClientRepository clients;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClients() {
        jdbcTemplate.update("DELETE FROM iam_session.oauth_refresh_tokens");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_authorizations");
        jdbcTemplate.update("DELETE FROM iam_session.sessions");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_client_events");
        jdbcTemplate.update("DELETE FROM iam_session.signing_key_events");
        jdbcTemplate.update("DELETE FROM iam_session.operator_operations");
        jdbcTemplate.update("DELETE FROM iam_session.oauth_clients");
        jdbcTemplate.update("DELETE FROM iam_session.signing_keys");
        clock.set(NOW);
    }

    @Test
    void v11SchemaPersistsConstrainedClientsAndReconciliationIsReplaySafe() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'iam_session' AND table_name = 'oauth_clients'
                """, String.class)).contains(
                "client_secret",
                "client_secret_expires_at",
                "authorization_grant_types",
                "client_settings",
                "token_settings",
                "version"
        );
        assertThat(readiness.readiness().ready()).isFalse();

        assertThat(registrations.reconcile(context("reconcile-1"))).isEqualTo(2);
        assertThat(registrations.reconcile(context("reconcile-1"))).isEqualTo(2);
        assertThat(registrations.reconcile(context("reconcile-2"))).isZero();

        RegisteredClient bff = clients.findByClientId(BFF_CLIENT_ID);
        assertThat(bff.getId()).isEqualTo("bff:" + BFF_CLIENT_ID);
        assertThat(bff.getAuthorizationGrantTypes()).containsExactlyInAnyOrder(
                AuthorizationGrantType.AUTHORIZATION_CODE,
                AuthorizationGrantType.REFRESH_TOKEN
        );
        assertThat(bff.getScopes()).containsExactly("api.access");
        assertThat(bff.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(bff.getTokenSettings().isReuseRefreshTokens()).isFalse();
        assertThat(passwordEncoder.matches(BFF_SECRET, bff.getClientSecret())).isTrue();

        RegisteredClient service = clients.findByClientId(SERVICE_CLIENT_ID);
        assertThat(service.getId()).isEqualTo("service:" + SERVICE_CLIENT_ID);
        assertThat(service.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(service.getScopes()).containsExactlyInAnyOrder(
                "iam.authorization.check",
                "iam.relationship.write"
        );
        assertThat(passwordEncoder.matches(SERVICE_SECRET, service.getClientSecret())).isTrue();
        assertThat(service.getClientSecret()).doesNotContain(SERVICE_SECRET);
        assertThat(readiness.readiness()).isEqualTo(new OAuthClientReadiness.Readiness(true, null));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.oauth_client_events",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT convalidated
                FROM pg_constraint
                WHERE conname = 'oauth_authorizations_registered_client_fk'
                """, Boolean.class)).isTrue();
    }

    @Test
    void correctsNonSecretDriftWithoutRestoringTheBootstrapSecret() {
        registrations.reconcile(context("reconcile-drift-initial"));
        String rotatedSecret = "n".repeat(32);
        credentials.rotateSecret(new RotateOAuthClientSecret(
                SERVICE_CLIENT_ID,
                rotatedSecret,
                NOW.plus(Duration.ofDays(30)),
                context("rotate-before-drift")
        ));
        RegisteredClient current = clients.findByClientId(SERVICE_CLIENT_ID);
        clients.save(RegisteredClient.from(current)
                .clientName("drifted service")
                .build());
        clients.flush();

        assertThat(readiness.readiness().ready()).isFalse();
        assertThat(registrations.reconcile(context("reconcile-drift"))).isOne();

        RegisteredClient repaired = clients.findByClientId(SERVICE_CLIENT_ID);
        assertThat(repaired.getClientName()).isEqualTo("VK Platform API service");
        assertThat(passwordEncoder.matches(rotatedSecret, repaired.getClientSecret())).isTrue();
        assertThat(passwordEncoder.matches(SERVICE_SECRET, repaired.getClientSecret())).isFalse();
        assertThat(repaired.getClientSecretExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void rotatesCredentialsWithImmediateReplaySafeCutoverAndRejectsConflicts() {
        registrations.reconcile(context("reconcile-rotate"));
        Instant expiresAt = NOW.plus(Duration.ofHours(2));
        RotateOAuthClientSecret command = new RotateOAuthClientSecret(
                SERVICE_CLIENT_ID,
                "r".repeat(32),
                expiresAt,
                context("rotate-1")
        );

        OAuthClientSecretRotation first = credentials.rotateSecret(command);
        OAuthClientSecretRotation retry = credentials.rotateSecret(command);

        assertThat(retry).isEqualTo(first);
        RegisteredClient service = clients.findByClientId(SERVICE_CLIENT_ID);
        assertThat(passwordEncoder.matches("r".repeat(32), service.getClientSecret())).isTrue();
        assertThat(passwordEncoder.matches(SERVICE_SECRET, service.getClientSecret())).isFalse();
        assertThat(service.getClientSecretExpiresAt()).isEqualTo(expiresAt);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM iam_session.oauth_client_events
                WHERE event_type = 'credential_rotated'
                """, Integer.class)).isOne();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT OCTET_LENGTH(request_fingerprint)
                FROM iam_session.operator_operations
                WHERE operation_id = 'rotate-1'
                """, Integer.class)).isEqualTo(32);

        assertThatThrownBy(() -> credentials.rotateSecret(new RotateOAuthClientSecret(
                SERVICE_CLIENT_ID,
                "x".repeat(32),
                expiresAt,
                context("rotate-1")
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already in use");
        assertThatThrownBy(() -> credentials.rotateSecret(new RotateOAuthClientSecret(
                "missing-client",
                "x".repeat(32),
                expiresAt,
                context("rotate-missing")
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> credentials.rotateSecret(new RotateOAuthClientSecret(
                SERVICE_CLIENT_ID,
                "x".repeat(32),
                NOW,
                context("rotate-expired")
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    @Test
    void roundTripsForwardCompatibleAuthenticationAndGrantTypeValues() {
        RegisteredClient custom = RegisteredClient.withId("custom:forward-compatible")
                .clientId("forward-compatible")
                .clientIdIssuedAt(NOW)
                .clientName("Forward compatible client")
                .clientAuthenticationMethod(new ClientAuthenticationMethod("urn:test:method"))
                .authorizationGrantType(new AuthorizationGrantType("urn:test:grant"))
                .scope("custom.scope")
                .clientSettings(ClientSettings.builder()
                        .setting("custom-client-setting", "kept")
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .setting("custom-token-setting", "kept")
                        .build())
                .build();

        clients.save(custom);
        clients.flush();
        RegisteredClient loaded = clients.findById(custom.getId());

        assertThat(loaded.getClientAuthenticationMethods())
                .extracting(ClientAuthenticationMethod::getValue)
                .containsExactly("urn:test:method");
        assertThat(loaded.getAuthorizationGrantTypes())
                .extracting(AuthorizationGrantType::getValue)
                .containsExactly("urn:test:grant");
        assertThat(loaded.getClientSettings().<String>getSetting("custom-client-setting"))
                .isEqualTo("kept");
        assertThat(loaded.getTokenSettings().<String>getSetting("custom-token-setting"))
                .isEqualTo("kept");
    }

    private static OperatorContext context(String operationId) {
        return new OperatorContext("integration-operator", operationId);
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

    private static byte[] protectionKey() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 9);
        return key;
    }

    private static String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }

        @Bean
        @Primary
        PasswordlessIdentityOperations passwordlessIdentityOperations() {
            return mock(PasswordlessIdentityOperations.class);
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void set(Instant value) {
            current.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
