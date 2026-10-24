package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.domain.SigningKeyStatus;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.infrastructure.config.SessionSecurityMetrics;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyOperationGuardEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.vkplatform.iam.session.SessionTestFixtures.properties;

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
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(SessionSecurityMetrics.class)
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
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(SessionSecurityMetrics.class)
        );

        assertThat(bootstrapStore.bootstrap(context()).id()).isEqualTo("bootstrap-key");
        assertThat(bootstrapStore.bootstrap(context()).id()).isEqualTo("bootstrap-key");

        verify(keys, times(1)).save(any(SigningKeyEntity.class));
        verify(events, times(1)).save(any());
    }

    @Test
    void rotatesAfterPersistingTheRetirementWindowAndPublishesOverlappingKeys() {
        SigningKeyEntity previous = entity("previous", keyPair(), NOW.minus(Duration.ofDays(1)));
        when(keys.findLockedByStatus(SigningKeyStatus.ACTIVE)).thenReturn(Optional.of(previous));

        var rotated = store.rotate(context());

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

        var published = store.publishedPublicKeys();

        assertThat(published).extracting(key -> key.getKeyID())
                .containsExactly(active.id(), previous.id());
        assertThat(published.get(0).isPrivate()).isFalse();
        assertThat(published.get(1).isPrivate()).isFalse();
    }

    @Test
    void retiresOnlyKeysWhosePublicationWindowHasElapsed() {
        SigningKeyEntity expired = entity("expired", keyPair(), NOW.minus(Duration.ofDays(1)));
        expired.beginRetirement(NOW.minusSeconds(1));
        when(keys.findLockedByStatusAndPublishUntilLessThanEqual(SigningKeyStatus.RETIRING, NOW))
                .thenReturn(List.of(expired));

        assertThat(store.retireExpired(context())).isEqualTo(1);

        assertThat(expired.status()).isEqualTo(SigningKeyStatus.RETIRED);
        assertThat(expired.retiredAt()).isEqualTo(NOW);
        verify(events).save(any());
    }

    @Test
    void reencryptsABoundedBatchAndVerifiesPlaintextBeforeMutation() {
        SigningKeyEntity key = entity("old-protection", keyPair(), NOW.minus(Duration.ofDays(1)));
        SigningKeyProtector protector = mock(SigningKeyProtector.class);
        byte[] plaintext = keyPair().getPrivate().getEncoded();
        byte[] replacement = new byte[]{4, 5, 6};
        byte[] nonce = new byte[12];
        when(protector.currentVersion()).thenReturn("v2");
        when(keys.findByProtectionKeyVersionNotOrderByIdAsc(
                org.mockito.ArgumentMatchers.eq("v2"),
                any(Pageable.class)
        )).thenReturn(List.of(key));
        when(protector.decrypt(
                org.mockito.ArgumentMatchers.eq(key.id()),
                org.mockito.ArgumentMatchers.eq("v1"),
                any(byte[].class),
                any(byte[].class)
        )).thenReturn(plaintext);
        when(protector.encrypt(key.id(), plaintext))
                .thenReturn(new SigningKeyProtector.EncryptedPrivateKey("v2", replacement, nonce));
        when(protector.decrypt(key.id(), "v2", replacement, nonce)).thenReturn(plaintext);
        JpaSigningKeyStore migrating = store(protector);

        assertThat(migrating.reencrypt(1, context())).isEqualTo(1);

        assertThat(key.protectionKeyVersion()).isEqualTo("v2");
        assertThat(key.encryptedPrivateKey()).containsExactly(replacement);
        verify(events).save(any());
    }

    @Test
    void failedReencryptionVerificationLeavesThePersistedProtectionUntouched() {
        SigningKeyEntity key = entity("old-protection", keyPair(), NOW.minus(Duration.ofDays(1)));
        SigningKeyProtector protector = mock(SigningKeyProtector.class);
        byte[] oldCiphertext = key.encryptedPrivateKey();
        byte[] plaintext = new byte[]{1};
        byte[] replacement = new byte[]{2};
        byte[] replacementNonce = new byte[12];
        when(protector.currentVersion()).thenReturn("v2");
        when(keys.findByProtectionKeyVersionNotOrderByIdAsc(
                org.mockito.ArgumentMatchers.eq("v2"),
                any(Pageable.class)
        )).thenReturn(List.of(key));
        when(protector.decrypt(
                org.mockito.ArgumentMatchers.eq(key.id()),
                org.mockito.ArgumentMatchers.eq("v1"),
                any(byte[].class),
                any(byte[].class)
        )).thenReturn(plaintext);
        when(protector.encrypt(key.id(), plaintext))
                .thenReturn(new SigningKeyProtector.EncryptedPrivateKey("v2", replacement, replacementNonce));
        when(protector.decrypt(key.id(), "v2", replacement, replacementNonce))
                .thenReturn(new byte[]{3});

        assertThatThrownBy(() -> store(protector).reencrypt(1, context()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verification failed");
        assertThat(key.protectionKeyVersion()).isEqualTo("v1");
        assertThat(key.encryptedPrivateKey()).containsExactly(oldCiphertext);
    }

    @Test
    void compromisingTheActiveKeyImmediatelyTerminalizesItAndCreatesAReplacement() {
        SigningKeyEntity compromised = entity("compromised-active", keyPair(), NOW.minusSeconds(10));
        when(keys.findLockedById("compromised-active")).thenReturn(Optional.of(compromised));

        store.compromise("compromised-active", context());

        assertThat(compromised.status()).isEqualTo(SigningKeyStatus.COMPROMISED);
        assertThat(compromised.publishUntil()).isEqualTo(NOW);
        assertThat(compromised.retiredAt()).isEqualTo(NOW);
        ArgumentCaptor<SigningKeyEntity> replacement = ArgumentCaptor.forClass(SigningKeyEntity.class);
        verify(keys).save(replacement.capture());
        assertThat(replacement.getValue().status()).isEqualTo(SigningKeyStatus.ACTIVE);
        assertThat(replacement.getValue().id()).startsWith("rsa-");
        verify(events, times(2)).save(any());
    }

    private static SigningKeyEntity entity(String keyId, KeyPair pair, Instant activatedAt) {
        return new SigningKeyEntity(
                keyId,
                pair.getPublic().getEncoded(),
                pair.getPrivate().getEncoded(),
                new byte[12],
                "v1",
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
            public String currentVersion() {
                return "v1";
            }

            @Override
            public EncryptedPrivateKey encrypt(String keyId, byte[] privateKey) {
                return new EncryptedPrivateKey("v1", privateKey, new byte[12]);
            }

            @Override
            public byte[] decrypt(String keyId, String version, byte[] ciphertext, byte[] nonce) {
                return ciphertext;
            }
        };
    }

    private JpaSigningKeyStore store(SigningKeyProtector protector) {
        return new JpaSigningKeyStore(
                guard,
                keys,
                events,
                protector,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                mock(SessionSecurityMetrics.class)
        );
    }

    private static OperatorContext context() {
        return new OperatorContext("security-operator", "signing-key-test");
    }
}
