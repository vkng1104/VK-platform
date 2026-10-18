package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.SigningKeySummary;
import com.vkplatform.iam.session.domain.SigningKeyEventType;
import com.vkplatform.iam.session.domain.SigningKeyStatus;
import com.vkplatform.iam.session.infrastructure.config.SessionProperties;
import com.vkplatform.iam.session.infrastructure.config.SessionSecurityMetrics;
import com.vkplatform.iam.session.infrastructure.crypto.RsaKeyLoader;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEventEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    private final SessionSecurityMetrics metrics;
    private final ConcurrentMap<String, RSAKey> publicKeys = new ConcurrentHashMap<>();
    private volatile CachedSigner cachedSigner;

    JpaSigningKeyStore(
            SpringDataSigningKeyOperationGuardRepository guard,
            SpringDataSigningKeyRepository keys,
            SpringDataSigningKeyEventRepository events,
            SigningKeyProtector protector,
            SessionProperties properties,
            Clock clock,
            SessionSecurityMetrics metrics
    ) {
        this.guard = guard;
        this.keys = keys;
        this.events = events;
        this.protector = protector;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public SigningKeySummary bootstrap(OperatorContext context) {
        Objects.requireNonNull(context);
        lockOperations();
        SigningKeyEntity active = keys.findByStatus(SigningKeyStatus.ACTIVE).orElse(null);
        if (active != null) {
            return summary(active);
        }
        SigningKeyEntity created = saveNewKey(
                requireKeyId(properties.signingKeyId()),
                RsaKeyLoader.load(
                        properties.signingPrivateKeyPkcs8Base64(),
                        properties.signingPublicKeyX509Base64()
                ),
                clock.instant(),
                SigningKeyEventType.BOOTSTRAPPED,
                context
        );
        invalidateAfterCommit();
        return summary(created);
    }

    @Override
    @Transactional
    public SigningKeySummary rotate(OperatorContext context) {
        Objects.requireNonNull(context);
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
                SigningKeyEventType.ACTIVATED,
                context
        );
        invalidateAfterCommit();
        metrics.signingKeyRotated();
        return summary(next);
    }

    @Override
    @Transactional
    public int retireExpired(OperatorContext context) {
        Objects.requireNonNull(context);
        lockOperations();
        Instant now = clock.instant();
        List<SigningKeyEntity> expired = keys.findLockedByStatusAndPublishUntilLessThanEqual(
                SigningKeyStatus.RETIRING,
                now
        );
        expired.forEach(key -> {
            key.retire(now);
            appendEvent(key.id(), SigningKeyEventType.RETIRED, context, now);
        });
        if (!expired.isEmpty()) {
            invalidateAfterCommit();
        }
        return expired.size();
    }

    @Override
    @Transactional
    public int reencrypt(int batchSize, OperatorContext context) {
        Objects.requireNonNull(context);
        if (batchSize <= 0 || batchSize > 1_000) {
            throw new IllegalArgumentException("signing-key re-encryption batch size is invalid");
        }
        lockOperations();
        List<SigningKeyEntity> batch = keys.findByProtectionKeyVersionNotOrderByIdAsc(
                protector.currentVersion(),
                PageRequest.of(0, batchSize)
        );
        Instant now = clock.instant();
        for (SigningKeyEntity key : batch) {
            byte[] plaintext = protector.decrypt(
                    key.id(),
                    key.protectionKeyVersion(),
                    key.encryptedPrivateKey(),
                    key.encryptionNonce()
            );
            SigningKeyProtector.EncryptedPrivateKey replacement = protector.encrypt(key.id(), plaintext);
            byte[] verified = protector.decrypt(
                    key.id(),
                    replacement.version(),
                    replacement.ciphertext(),
                    replacement.nonce()
            );
            if (!java.security.MessageDigest.isEqual(plaintext, verified)) {
                throw new IllegalStateException("signing-key re-encryption verification failed");
            }
            key.replaceProtection(
                    replacement.version(),
                    replacement.ciphertext(),
                    replacement.nonce()
            );
            appendEvent(key.id(), SigningKeyEventType.REENCRYPTED, context, now);
        }
        if (!batch.isEmpty()) {
            invalidateAfterCommit();
        }
        return batch.size();
    }

    @Override
    @Transactional
    public SigningKeySummary compromise(String keyId, OperatorContext context) {
        Objects.requireNonNull(context);
        lockOperations();
        Instant now = clock.instant();
        SigningKeyEntity compromised = keys.findLockedById(requireKeyId(keyId))
                .orElseThrow(() -> new IllegalArgumentException("signing key was not found"));
        if (compromised.status() == SigningKeyStatus.COMPROMISED
                || compromised.status() == SigningKeyStatus.RETIRED) {
            return summary(compromised);
        }
        boolean replaceActive = compromised.status() == SigningKeyStatus.ACTIVE;
        compromised.compromise(now);
        appendEvent(compromised.id(), SigningKeyEventType.COMPROMISED, context, now);
        if (replaceActive) {
            keys.flush();
            saveNewKey(
                    "rsa-" + UUID.randomUUID(),
                    generateKeyPair(),
                    now,
                    SigningKeyEventType.ACTIVATED,
                    context
            );
        }
        invalidateAfterCommit();
        return summary(compromised);
    }

    @Transactional(readOnly = true)
    public RSAKey activeSigner() {
        SigningKeyEntity active = keys.findByStatus(SigningKeyStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("active signing key is missing"));
        CachedSigner current = cachedSigner;
        if (current != null && current.keyId().equals(active.id()) && current.version() == active.version()) {
            return current.key();
        }
        synchronized (this) {
            current = cachedSigner;
            if (current == null || !current.keyId().equals(active.id()) || current.version() != active.version()) {
                current = new CachedSigner(active.id(), active.version(), toJwk(active, true));
                cachedSigner = current;
            }
            return current.key();
        }
    }

    @Transactional(readOnly = true)
    public boolean hasUsableActiveKey() {
        try {
            activeSigner();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public List<RSAKey> publishedPublicKeys() {
        Instant now = clock.instant();
        List<RSAKey> result = new ArrayList<>();
        keys.findByStatus(SigningKeyStatus.ACTIVE).ifPresent(key -> result.add(publicJwk(key)));
        keys.findByStatusAndPublishUntilAfter(SigningKeyStatus.RETIRING, now)
                .forEach(key -> result.add(publicJwk(key)));
        if (result.isEmpty()) {
            throw new IllegalStateException("published signing keys are missing");
        }
        return List.copyOf(result);
    }

    private SigningKeyEntity saveNewKey(
            String keyId,
            KeyPair pair,
            Instant now,
            SigningKeyEventType eventType,
            OperatorContext context
    ) {
        SigningKeyProtector.EncryptedPrivateKey encrypted = protector.encrypt(
                keyId,
                pair.getPrivate().getEncoded()
        );
        SigningKeyEntity entity = keys.save(new SigningKeyEntity(
                keyId,
                pair.getPublic().getEncoded(),
                encrypted.ciphertext(),
                encrypted.nonce(),
                encrypted.version(),
                now
        ));
        appendEvent(keyId, eventType, context, now);
        return entity;
    }

    private void appendEvent(
            String keyId,
            SigningKeyEventType type,
            OperatorContext context,
            Instant now
    ) {
        events.save(new SigningKeyEventEntity(
                UUID.randomUUID(),
                keyId,
                type,
                context.actorId(),
                context.operationId(),
                now
        ));
    }

    private RSAKey publicJwk(SigningKeyEntity entity) {
        return publicKeys.computeIfAbsent(entity.id(), ignored -> toJwk(entity, false));
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
                        entity.protectionKeyVersion(),
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

    private void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cachedSigner = null;
                    publicKeys.clear();
                }
            });
        } else {
            cachedSigner = null;
            publicKeys.clear();
        }
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
            throw new IllegalStateException("signing key ID must be configured");
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

    private record CachedSigner(String keyId, long version, RSAKey key) {
    }
}
