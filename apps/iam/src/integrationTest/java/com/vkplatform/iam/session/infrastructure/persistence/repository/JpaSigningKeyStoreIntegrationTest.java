package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.SigningKeySummary;
import com.vkplatform.iam.session.domain.SigningKeyStatus;
import com.vkplatform.iam.session.infrastructure.crypto.AesGcmSigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
        "iam.signing-keys.protection-version=v2",
        "iam.signing-keys.publication-safety-overlap=PT1M",
        "iam.signing-keys.retirement-enabled=false"
})
@Import(JpaSigningKeyStoreIntegrationTest.TestClockConfiguration.class)
class JpaSigningKeyStoreIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-29T04:00:00Z");
    private static final byte[] V1_KEY = filled((byte) 1);
    private static final byte[] V2_KEY = filled((byte) 2);
    private static final KeyPair BOOTSTRAP_PAIR = keyPair(2048);

    @DynamicPropertySource
    static void signingProperties(DynamicPropertyRegistry properties) {
        properties.add("iam.oauth.signing-key-id", () -> "bootstrap-key");
        properties.add("iam.oauth.signing-private-key-pkcs8-base64", () -> encoded(
                BOOTSTRAP_PAIR.getPrivate().getEncoded()
        ));
        properties.add("iam.oauth.signing-public-key-x509-base64", () -> encoded(
                BOOTSTRAP_PAIR.getPublic().getEncoded()
        ));
        properties.add("iam.signing-keys.protection-keys", () ->
                "v1=" + encoded(V1_KEY) + ",v2=" + encoded(V2_KEY));
    }

    @Autowired
    private SigningKeyOperations operations;

    @Autowired
    private JpaSigningKeyStore store;

    @Autowired
    private SpringDataSigningKeyRepository repository;

    @Autowired
    private SigningKeyProtector protector;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetSigningKeys() {
        jdbcTemplate.update("DELETE FROM iam_session.signing_key_events");
        jdbcTemplate.update("DELETE FROM iam_session.operator_operations");
        jdbcTemplate.update("DELETE FROM iam_session.signing_keys");
        clock.set(NOW);
    }

    @Test
    void v10SchemaConstrainsAndIndexesThePersistentKeyLifecycle() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'iam_session' AND table_name = 'signing_keys'
                """, String.class)).contains(
                "encrypted_private_key",
                "encryption_nonce",
                "protection_key_version",
                "publish_until",
                "retired_at",
                "compromised_at",
                "version"
        );
        assertThat(jdbcTemplate.queryForList("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'iam_session' AND tablename = 'signing_keys'
                """, String.class)).contains(
                "signing_keys_one_active_idx",
                "signing_keys_publication_idx",
                "signing_keys_protection_version_id_idx"
        );
    }

    @Test
    void bootstrapsOnlyExplicitlyEncryptsThePrivateKeyAndReplaysFromTheLedger() {
        assertThat(store.readiness().usable()).isFalse();

        SigningKeySummary first = operations.bootstrap(context("bootstrap-1"));
        SigningKeySummary retry = operations.bootstrap(context("bootstrap-1"));

        assertThat(first.id()).isEqualTo("bootstrap-key");
        assertThat(retry).isEqualTo(first);
        assertThat(store.readiness().usable()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.signing_keys",
                Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.signing_key_events",
                Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.operator_operations",
                Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT protection_key_version FROM iam_session.signing_keys",
                String.class
        )).isEqualTo("v2");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT encryption_nonce FROM iam_session.signing_keys",
                byte[].class
        )).hasSize(12);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT encrypted_private_key FROM iam_session.signing_keys",
                byte[].class
        )).isNotEqualTo(BOOTSTRAP_PAIR.getPrivate().getEncoded());

        assertThatThrownBy(() -> operations.rotate(context("bootstrap-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already in use");
    }

    @Test
    void rotatesWithAnOverlapPublishesBothKeysAndRetiresAtTheBoundary() throws Exception {
        SigningKeySummary original = operations.bootstrap(context("bootstrap-rotate"));
        clock.set(NOW.plusSeconds(10));

        SigningKeySummary active = operations.rotate(context("rotate-1"));

        assertThat(active.id()).startsWith("rsa-").isNotEqualTo(original.id());
        Instant deadline = NOW.plusSeconds(10).plus(Duration.ofMinutes(6));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT publish_until FROM iam_session.signing_keys WHERE id = ?",
                java.time.OffsetDateTime.class,
                original.id()
        ).toInstant()).isEqualTo(deadline);
        assertThat(store.select(new JWKSelector(new JWKMatcher.Builder().build())))
                .extracting(key -> key.getKeyID())
                .containsExactlyInAnyOrder(original.id(), active.id());

        clock.set(deadline.minusMillis(1));
        assertThat(operations.retireExpired(context("retire-too-early"))).isZero();
        clock.set(deadline);
        assertThat(operations.retireExpired(context("retire-at-boundary"))).isOne();
        assertThat(operations.retireExpired(context("retire-at-boundary"))).isOne();
        assertThat(store.select(new JWKSelector(new JWKMatcher.Builder().build())))
                .extracting(key -> key.getKeyID())
                .containsExactly(active.id());
    }

    @Test
    void compromisingTheActiveKeyRemovesItFromPublicationAndCreatesOneReplacement() {
        SigningKeySummary compromised = operations.bootstrap(context("bootstrap-compromise"));
        clock.set(NOW.plusSeconds(5));

        SigningKeySummary result = operations.compromise(
                compromised.id(),
                context("compromise-1")
        );
        SigningKeySummary retry = operations.compromise(
                compromised.id(),
                context("compromise-1")
        );

        assertThat(result.status()).isEqualTo("COMPROMISED");
        assertThat(retry).isEqualTo(result);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM iam_session.signing_keys WHERE status = 'ACTIVE'
                """, Integer.class)).isOne();
        assertThat(store.select(new JWKSelector(new JWKMatcher.Builder().build())))
                .extracting(key -> key.getKeyID())
                .doesNotContain(compromised.id())
                .hasSize(1);
    }

    @Test
    void publicLookupNeverDecryptsPrivateMaterialButReadinessDetectsAMissingProtectionVersion() {
        SigningKeySummary active = operations.bootstrap(context("bootstrap-readiness"));
        jdbcTemplate.update("""
                UPDATE iam_session.signing_keys
                SET protection_key_version = 'missing-version', version = version + 1
                WHERE id = ?
                """, active.id());

        assertThat(store.select(new JWKSelector(new JWKMatcher.Builder().build())))
                .extracting(key -> key.getKeyID())
                .containsExactly(active.id());
        assertThat(store.readiness())
                .isEqualTo(new com.vkplatform.iam.session.infrastructure.crypto.SigningKeyRuntime.Readiness(
                        false,
                        "active_signing_key_unusable"
                ));
    }

    @Test
    void reencryptsABoundedOldVersionBatchAndVerifiesTheReplacement() {
        KeyPair pair = keyPair(2048);
        AesGcmSigningKeyProtector oldProtector = new AesGcmSigningKeyProtector(
                "v1",
                Map.of("v1", encoded(V1_KEY)),
                new java.security.SecureRandom()
        );
        SigningKeyProtector.EncryptedPrivateKey old = oldProtector.encrypt(
                "old-protection",
                pair.getPrivate().getEncoded()
        );
        repository.saveAndFlush(new SigningKeyEntity(
                "old-protection",
                pair.getPublic().getEncoded(),
                old.ciphertext(),
                old.nonce(),
                old.version(),
                NOW
        ));

        assertThat(operations.reencrypt(1, context("reencrypt-1"))).isOne();
        assertThat(operations.reencrypt(1, context("reencrypt-1"))).isOne();

        byte[] ciphertext = jdbcTemplate.queryForObject(
                "SELECT encrypted_private_key FROM iam_session.signing_keys",
                byte[].class
        );
        byte[] nonce = jdbcTemplate.queryForObject(
                "SELECT encryption_nonce FROM iam_session.signing_keys",
                byte[].class
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT protection_key_version FROM iam_session.signing_keys",
                String.class
        )).isEqualTo("v2");
        assertThat(protector.decrypt("old-protection", "v2", ciphertext, nonce))
                .containsExactly(pair.getPrivate().getEncoded());
    }

    @Test
    void databaseRejectsASecondActiveKeyAndConcurrentRotationsRemainSerialized() throws Exception {
        operations.bootstrap(context("bootstrap-concurrent"));

        CompletableFuture<SigningKeySummary> first = CompletableFuture.supplyAsync(() ->
                operations.rotate(context("rotate-concurrent-1"))
        );
        CompletableFuture<SigningKeySummary> second = CompletableFuture.supplyAsync(() ->
                operations.rotate(context("rotate-concurrent-2"))
        );
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        assertThat(first.get().id()).isNotEqualTo(second.get().id());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.signing_keys WHERE status = 'ACTIVE'",
                Integer.class
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM iam_session.signing_keys WHERE status = 'RETIRING'",
                Integer.class
        )).isEqualTo(2);

        KeyPair extraPair = keyPair(2048);
        SigningKeyProtector.EncryptedPrivateKey encrypted = protector.encrypt(
                "forbidden-second-active",
                extraPair.getPrivate().getEncoded()
        );
        assertThatThrownBy(() -> repository.saveAndFlush(new SigningKeyEntity(
                "forbidden-second-active",
                extraPair.getPublic().getEncoded(),
                encrypted.ciphertext(),
                encrypted.nonce(),
                encrypted.version(),
                NOW
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static OperatorContext context(String operationId) {
        return new OperatorContext("integration-operator", operationId);
    }

    private static KeyPair keyPair(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] filled(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
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
