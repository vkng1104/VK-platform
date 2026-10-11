package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.result.SigningKeySummary;
import com.vkplatform.iam.session.domain.SigningKeyStatus;
import com.vkplatform.iam.session.infrastructure.config.SessionProperties;
import com.vkplatform.iam.session.infrastructure.crypto.RsaKeyLoader;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEventEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class JpaSigningKeyStore implements SigningKeyOperations {
    private static final short OPERATION_GUARD_ID = 1;
    private static final Duration PUBLICATION_SAFETY_WINDOW = Duration.ofMinutes(1);

    private final SpringDataSigningKeyOperationGuardRepository guard;
    private final SpringDataSigningKeyRepository keys;
    private final SpringDataSigningKeyEventRepository events;
    private final SigningKeyProtector protector;
    private final SessionProperties properties;
    private final Clock clock;

    JpaSigningKeyStore(
            SpringDataSigningKeyOperationGuardRepository guard,
            SpringDataSigningKeyRepository keys,
            SpringDataSigningKeyEventRepository events,
            SigningKeyProtector protector,
            SessionProperties properties,
            Clock clock
    ) {
        this.guard = guard;
        this.keys = keys;
        this.events = events;
        this.protector = protector;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SigningKeySummary bootstrap() {
        lockOperations();
        SigningKeyEntity active = keys.findByStatus(SigningKeyStatus.ACTIVE).orElse(null);
        if (active != null) {
            return summary(active);
        }
        String keyId = requireKeyId(properties.signingKeyId());
        KeyPair configured = RsaKeyLoader.load(
                properties.signingPrivateKeyPkcs8Base64(),
                properties.signingPublicKeyX509Base64()
        );
        return summary(saveNewKey(keyId, configured, clock.instant(), "bootstrapped"));
    }

    @Override
    @Transactional
    public SigningKeySummary rotate() {
        lockOperations();
        Instant now = clock.instant();
        SigningKeyEntity active = keys.findLockedByStatus(SigningKeyStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("active signing key is missing"));
        active.beginRetirement(now.plus(properties.accessTokenTtl()).plus(PUBLICATION_SAFETY_WINDOW));
        keys.flush();
        SigningKeyEntity next = saveNewKey(
                "rsa-" + UUID.randomUUID(),
                generateKeyPair(),
                now,
                "activated"
        );
        return summary(next);
    }

    @Override
    @Transactional
    public int retireExpired() {
        lockOperations();
        Instant now = clock.instant();
        List<SigningKeyEntity> expired = keys.findLockedByStatusAndPublishUntilLessThanEqual(
                SigningKeyStatus.RETIRING,
                now
        );
        expired.forEach(key -> {
            key.retire(now);
            events.save(new SigningKeyEventEntity(UUID.randomUUID(), key.id(), "retired", now));
        });
        return expired.size();
    }

    @Transactional(readOnly = true)
    public String activeKeyId() {
        return keys.findByStatus(SigningKeyStatus.ACTIVE)
                .map(SigningKeyEntity::id)
                .orElseThrow(() -> new IllegalStateException("active signing key is missing"));
    }

    @Transactional(readOnly = true)
    public List<RSAKey> publishedKeys() {
        Instant now = clock.instant();
        List<RSAKey> result = new ArrayList<>();
        keys.findByStatus(SigningKeyStatus.ACTIVE).ifPresent(key -> result.add(toJwk(key, true)));
        keys.findByStatusAndPublishUntilAfter(SigningKeyStatus.RETIRING, now)
                .forEach(key -> result.add(toJwk(key, false)));
        if (result.isEmpty()) {
            throw new IllegalStateException("published signing keys are missing");
        }
        return List.copyOf(result);
    }

    private SigningKeyEntity saveNewKey(String keyId, KeyPair pair, Instant now, String eventType) {
        SigningKeyProtector.EncryptedPrivateKey encrypted = protector.encrypt(
                keyId,
                pair.getPrivate().getEncoded()
        );
        SigningKeyEntity entity = keys.save(new SigningKeyEntity(
                keyId,
                pair.getPublic().getEncoded(),
                encrypted.ciphertext(),
                encrypted.nonce(),
                now
        ));
        events.save(new SigningKeyEventEntity(UUID.randomUUID(), keyId, eventType, now));
        return entity;
    }

    private RSAKey toJwk(SigningKeyEntity entity, boolean includePrivateKey) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(entity.publicKeyX509())
            );
            RSAKey.Builder builder = new RSAKey.Builder(publicKey)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(entity.id());
            if (includePrivateKey) {
                byte[] privateKey = protector.decrypt(
                        entity.id(),
                        entity.encryptedPrivateKey(),
                        entity.encryptionNonce()
                );
                builder.privateKey((RSAPrivateKey) factory.generatePrivate(
                        new PKCS8EncodedKeySpec(privateKey)
                ));
            }
            return builder.build();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("persisted signing key is invalid", exception);
        }
    }

    private void lockOperations() {
        guard.findLockedById(OPERATION_GUARD_ID)
                .orElseThrow(() -> new IllegalStateException("signing-key operation guard is missing"));
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("RSA signing key cannot be generated", exception);
        }
    }

    private static String requireKeyId(String keyId) {
        if (keyId == null || keyId.isBlank() || keyId.length() > 128) {
            throw new IllegalStateException("IAM_SIGNING_KEY_ID must be configured");
        }
        return keyId.strip();
    }

    private static SigningKeySummary summary(SigningKeyEntity key) {
        return new SigningKeySummary(
                key.id(),
                key.algorithm(),
                key.status().name(),
                key.activatedAt(),
                key.publishUntil(),
                key.retiredAt()
        );
    }
}
