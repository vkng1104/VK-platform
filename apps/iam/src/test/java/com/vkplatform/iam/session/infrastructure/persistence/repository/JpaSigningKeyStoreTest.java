package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.domain.SigningKeyStatus;
import com.vkplatform.iam.session.infrastructure.config.SessionProperties;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyOperationGuardEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaSigningKeyStoreTest {
    private static final Instant NOW = Instant.parse("2026-10-11T09:30:00Z");

    private SpringDataSigningKeyOperationGuardRepository guard;
    private SpringDataSigningKeyRepository keys;
    private SpringDataSigningKeyEventRepository events;
    private JpaSigningKeyStore store;

    @BeforeEach
    void setUp() {
        guard = mock(SpringDataSigningKeyOperationGuardRepository.class);
        keys = mock(SpringDataSigningKeyRepository.class);
        events = mock(SpringDataSigningKeyEventRepository.class);
        when(guard.findLockedById((short) 1))
                .thenReturn(Optional.of(mock(SigningKeyOperationGuardEntity.class)));
        when(keys.save(any(SigningKeyEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        store = new JpaSigningKeyStore(
                guard,
                keys,
                events,
                passThroughProtector(),
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void bootstrapsTheConfiguredKeyOnlyWhenExplicitlyInvokedAndRemainsIdempotent() {
        KeyPair configured = keyPair();
        AtomicReference<SigningKeyEntity> persisted = new AtomicReference<>();
        when(keys.findByStatus(SigningKeyStatus.ACTIVE))
                .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        when(keys.save(any(SigningKeyEntity.class))).thenAnswer(invocation -> {
            SigningKeyEntity entity = invocation.getArgument(0);
            persisted.set(entity);
            return entity;
        });
        JpaSigningKeyStore bootstrapStore = new JpaSigningKeyStore(
                guard,
                keys,
                events,
                passThroughProtector(),
                properties(configured),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(bootstrapStore.bootstrap().id()).isEqualTo("bootstrap-key");
        assertThat(bootstrapStore.bootstrap().id()).isEqualTo("bootstrap-key");

        verify(keys, times(1)).save(any(SigningKeyEntity.class));
        verify(events, times(1)).save(any());
    }

    @Test
    void rotatesAfterPersistingTheRetirementWindowAndPublishesOverlappingKeys() {
        SigningKeyEntity previous = entity("previous", keyPair(), NOW.minus(Duration.ofDays(1)));
        when(keys.findLockedByStatus(SigningKeyStatus.ACTIVE)).thenReturn(Optional.of(previous));

        var rotated = store.rotate();

        assertThat(previous.status()).isEqualTo(SigningKeyStatus.RETIRING);
        assertThat(previous.publishUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(6)));
        assertThat(rotated.id()).startsWith("rsa-");
        InOrder persistenceOrder = inOrder(keys);
        persistenceOrder.verify(keys).flush();
        persistenceOrder.verify(keys).save(any(SigningKeyEntity.class));

        ArgumentCaptor<SigningKeyEntity> savedKey = ArgumentCaptor.forClass(SigningKeyEntity.class);
        verify(keys).save(savedKey.capture());
        SigningKeyEntity active = savedKey.getValue();
        when(keys.findByStatus(SigningKeyStatus.ACTIVE)).thenReturn(Optional.of(active));
        when(keys.findByStatusAndPublishUntilAfter(SigningKeyStatus.RETIRING, NOW))
                .thenReturn(List.of(previous));

        var published = store.publishedKeys();

        assertThat(published).extracting(key -> key.getKeyID())
                .containsExactly(active.id(), previous.id());
        assertThat(published.get(0).isPrivate()).isTrue();
        assertThat(published.get(1).isPrivate()).isFalse();
    }

    @Test
    void retiresOnlyKeysWhosePublicationWindowHasElapsed() {
        SigningKeyEntity expired = entity("expired", keyPair(), NOW.minus(Duration.ofDays(1)));
        expired.beginRetirement(NOW.minusSeconds(1));
        when(keys.findLockedByStatusAndPublishUntilLessThanEqual(SigningKeyStatus.RETIRING, NOW))
                .thenReturn(List.of(expired));

        assertThat(store.retireExpired()).isEqualTo(1);

        assertThat(expired.status()).isEqualTo(SigningKeyStatus.RETIRED);
        assertThat(expired.retiredAt()).isEqualTo(NOW);
        verify(events).save(any());
    }

    private static SigningKeyEntity entity(String keyId, KeyPair pair, Instant activatedAt) {
        return new SigningKeyEntity(
                keyId,
                pair.getPublic().getEncoded(),
                pair.getPrivate().getEncoded(),
                new byte[12],
                activatedAt
        );
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SigningKeyProtector passThroughProtector() {
        return new SigningKeyProtector() {
            @Override
            public EncryptedPrivateKey encrypt(String keyId, byte[] privateKey) {
                return new EncryptedPrivateKey(privateKey, new byte[12]);
            }

            @Override
            public byte[] decrypt(String keyId, byte[] ciphertext, byte[] nonce) {
                return ciphertext;
            }
        };
    }

    private static SessionProperties properties() {
        return properties(null);
    }

    private static SessionProperties properties(KeyPair configured) {
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
                configured == null ? "" : Base64.getEncoder().encodeToString(
                        configured.getPrivate().getEncoded()
                ),
                configured == null ? "" : Base64.getEncoder().encodeToString(
                        configured.getPublic().getEncoded()
                ),
                "",
                "b".repeat(32),
                URI.create("https://web.example.test/api/auth/callback"),
                "vk-platform-api",
                "s".repeat(32),
                "vk-platform-iam"
        );
    }
}
