package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.SigningKeySummary;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import com.vkplatform.iam.session.domain.SigningKeyEventType;
import com.vkplatform.iam.session.domain.SigningKeyStatus;
import com.vkplatform.iam.session.infrastructure.config.OAuthAuthorizationProperties;
import com.vkplatform.iam.session.infrastructure.config.OAuthProperties;
import com.vkplatform.iam.session.infrastructure.config.SigningKeyProperties;
import com.vkplatform.iam.session.infrastructure.crypto.RsaKeyLoader;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyProtector;
import com.vkplatform.iam.session.infrastructure.crypto.SigningKeyRuntime;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OperatorOperationEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEventEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(name = "iam.oauth.enabled", havingValue = "true")
public class JpaSigningKeyStore implements SigningKeyOperations, SigningKeyRuntime {
    private static final short OPERATION_GUARD_ID = 1;
    private static final String BOOTSTRAP = "bootstrap-signing-key";
    private static final String ROTATE = "rotate-signing-key";
    private static final String RETIRE = "retire-signing-keys";
    private static final String REENCRYPT = "reencrypt-signing-keys";
    private static final String COMPROMISE = "compromise-signing-key";

    private final SpringDataSigningKeyOperationGuardRepository guard;
    private final SpringDataSigningKeyRepository keys;
    private final SpringDataSigningKeyEventRepository events;
    private final SpringDataOperatorOperationRepository operations;
    private final SigningKeyProtector protector;
    private final SigningKeyProperties properties;
    private final OAuthProperties bootstrapProperties;
    private final OAuthAuthorizationProperties authorizationProperties;
    private final Clock clock;
    private final IamSecurityTelemetry telemetry;
    private final ConcurrentMap<String, CachedSigner> signerCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CachedPublicKey> publicKeyCache = new ConcurrentHashMap<>();

    JpaSigningKeyStore(
            SpringDataSigningKeyOperationGuardRepository guard,
            SpringDataSigningKeyRepository keys,
            SpringDataSigningKeyEventRepository events,
            SpringDataOperatorOperationRepository operations,
            SigningKeyProtector protector,
            SigningKeyProperties properties,
            OAuthProperties bootstrapProperties,
            OAuthAuthorizationProperties authorizationProperties,
            IamSecurityTelemetry telemetry,
            Clock clock
    ) {
        this.guard = guard;
        this.keys = keys;
        this.events = events;
        this.operations = operations;
        this.protector = protector;
        this.properties = properties;
        this.bootstrapProperties = bootstrapProperties;
        this.authorizationProperties = authorizationProperties;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SigningKeySummary bootstrap(OperatorContext context) {
        Objects.requireNonNull(context);
        lockOperations();
        OperatorOperationEntity retry = retry(BOOTSTRAP, null, context);
        if (retry != null) {
            return completedKey(retry);
        }
        Instant now = clock.instant();
        SigningKeyEntity active = keys.findLockedByStatus(SigningKeyStatus.ACTIVE).orElse(null);
        if (active == null) {
            active = saveNewKey(
                    requireBootstrapKeyId(),
                    loadBootstrapPair(),
                    now,
                    SigningKeyEventType.BOOTSTRAPPED,
                    context
            );
            invalidateAfterCommit();
        }
        complete(BOOTSTRAP, null, active.id(), null, context, now);
        return summary(active);
    }

    @Override
    @Transactional
    public SigningKeySummary rotate(OperatorContext context) {
        Objects.requireNonNull(context);
        long startedAt = telemetry.start();
        lockOperations();
        OperatorOperationEntity retry = retry(ROTATE, null, context);
        if (retry != null) {
            telemetry.record(
                    IamSecurityTelemetry.Operation.SIGNING_KEY_ROTATION,
                    IamSecurityTelemetry.Outcome.NOOP,
                    startedAt
            );
            return completedKey(retry);
        }
        Instant now = clock.instant();
        SigningKeyEntity active = keys.findLockedByStatus(SigningKeyStatus.ACTIVE)
                .orElseThrow(() -> new IllegalStateException("active signing key is missing"));
        active.beginRetirement(
                now,
                now.plus(authorizationProperties.accessTokenTtl())
                        .plus(properties.publicationSafetyOverlap())
        );
        appendEvent(active.id(), SigningKeyEventType.ROTATION_STARTED, context, now);
        keys.flush();
        SigningKeyEntity next = saveNewKey(
                generatedKeyId(),
                generateKeyPair(),
                now,
                SigningKeyEventType.ACTIVATED,
                context
        );
        complete(ROTATE, null, next.id(), null, context, now);
        invalidateAfterCommit();
        telemetry.recordAfterCommit(
                IamSecurityTelemetry.Operation.SIGNING_KEY_ROTATION,
                IamSecurityTelemetry.Outcome.SUCCESS,
                startedAt
        );
        return summary(next);
    }

    @Override
    @Transactional
    public int retireExpired(OperatorContext context) {
        Objects.requireNonNull(context);
        lockOperations();
        OperatorOperationEntity retry = retry(RETIRE, null, context);
        if (retry != null) {
            return completedCount(retry);
        }
        Instant now = clock.instant();
        List<SigningKeyEntity> expired = keys
                .findLockedByStatusAndPublishUntilLessThanEqualOrderByIdAsc(
                        SigningKeyStatus.RETIRING,
                        now
                );
        int retired = 0;
        for (SigningKeyEntity key : expired) {
            if (key.retire(now)) {
                appendEvent(key.id(), SigningKeyEventType.RETIRED, context, now);
                retired++;
            }
        }
        complete(RETIRE, null, null, retired, context, now);
        if (retired > 0) {
            invalidateAfterCommit();
        }
        return retired;
    }

    @Override
    @Transactional
    public int reencrypt(int batchSize, OperatorContext context) {
        Objects.requireNonNull(context);
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("signing-key re-encryption batch size is invalid");
        }
        lockOperations();
        String target = Integer.toString(batchSize);
        OperatorOperationEntity retry = retry(REENCRYPT, target, context);
        if (retry != null) {
            return completedCount(retry);
        }
        List<SigningKeyEntity> batch = keys.findByProtectionKeyVersionNotOrderByIdAsc(
                protector.currentVersion(),
                PageRequest.of(0, batchSize)
        );
        Instant now = clock.instant();
        for (SigningKeyEntity key : batch) {
            reencrypt(key, context, now);
        }
        complete(REENCRYPT, target, null, batch.size(), context, now);
        if (!batch.isEmpty()) {
            invalidateAfterCommit();
        }
        return batch.size();
    }

    @Override
    @Transactional
    public SigningKeySummary compromise(String keyId, OperatorContext context) {
        Objects.requireNonNull(context);
        long startedAt = telemetry.start();
        String requiredKeyId = requireKeyId(keyId);
        lockOperations();
        OperatorOperationEntity retry = retry(COMPROMISE, requiredKeyId, context);
        if (retry != null) {
            telemetry.record(
                    IamSecurityTelemetry.Operation.SIGNING_KEY_COMPROMISE,
                    IamSecurityTelemetry.Outcome.NOOP,
                    startedAt
            );
            return completedKey(retry);
        }
        Instant now = clock.instant();
        SigningKeyEntity compromised = keys.findLockedById(requiredKeyId)
                .orElseThrow(() -> new IllegalArgumentException("signing key was not found"));
        boolean replaceActive = compromised.status() == SigningKeyStatus.ACTIVE;
        if (compromised.compromise(now)) {
            appendEvent(compromised.id(), SigningKeyEventType.COMPROMISED, context, now);
            if (replaceActive) {
                keys.flush();
                saveNewKey(
                        generatedKeyId(),
                        generateKeyPair(),
                        now,
                        SigningKeyEventType.ACTIVATED,
                        context
                );
            }
            invalidateAfterCommit();
        }
        complete(COMPROMISE, requiredKeyId, compromised.id(), null, context, now);
        telemetry.recordAfterCommit(
                IamSecurityTelemetry.Operation.SIGNING_KEY_COMPROMISE,
                IamSecurityTelemetry.Outcome.SUCCESS,
                startedAt
        );
        return summary(compromised);
    }

    @Transactional(readOnly = true)
    @Override
    public String activeSigningKeyId() {
        return keys.findByStatus(SigningKeyStatus.ACTIVE)
                .map(SigningKeyEntity::id)
                .orElseThrow(() -> new IllegalStateException("active signing key is missing"));
    }

    @Transactional(readOnly = true)
    @Override
    public List<JWK> select(JWKSelector selector) {
        long startedAt = telemetry.start();
        Set<String> requestedKeyIds = selector.getMatcher().getKeyIDs();
        if (requestedKeyIds != null && !requestedKeyIds.isEmpty()) {
            List<JWK> candidates = requestedKeyIds.stream()
                    .map(this::signerIfPublished)
                    .flatMap(java.util.Optional::stream)
                    .map(key -> (JWK) key)
                    .toList();
            List<JWK> selected = selector.select(new JWKSet(candidates));
            telemetry.record(
                    IamSecurityTelemetry.Operation.JWK_LOOKUP,
                    selected.isEmpty()
                            ? IamSecurityTelemetry.Outcome.NOT_FOUND
                            : IamSecurityTelemetry.Outcome.SUCCESS,
                    startedAt
            );
            return selected;
        }
        List<JWK> selected = selector.select(new JWKSet(publishedPublicKeys()));
        telemetry.record(
                IamSecurityTelemetry.Operation.JWK_LOOKUP,
                selected.isEmpty()
                        ? IamSecurityTelemetry.Outcome.NOT_FOUND
                        : IamSecurityTelemetry.Outcome.SUCCESS,
                startedAt
        );
        return selected;
    }

    @Transactional(readOnly = true)
    @Override
    public Readiness readiness() {
        try {
            SigningKeyEntity active = keys.findByStatus(SigningKeyStatus.ACTIVE)
                    .orElse(null);
            if (active == null) {
                return new Readiness(false, "active_signing_key_missing");
            }
            signer(active);
            return new Readiness(true, null);
        } catch (RuntimeException exception) {
            return new Readiness(false, "active_signing_key_unusable");
        }
    }

    private List<JWK> publishedPublicKeys() {
        Instant now = clock.instant();
        List<JWK> published = new ArrayList<>();
        keys.findByStatus(SigningKeyStatus.ACTIVE).ifPresent(key -> published.add(publicJwk(key)));
        keys.findByStatusAndPublishUntilAfterOrderByActivatedAtDesc(SigningKeyStatus.RETIRING, now)
                .forEach(key -> published.add(publicJwk(key)));
        if (published.isEmpty()) {
            throw new IllegalStateException("published signing keys are missing");
        }
        return List.copyOf(published);
    }

    private java.util.Optional<RSAKey> signerIfPublished(String keyId) {
        SigningKeyEntity key = keys.findById(keyId).orElse(null);
        if (key == null) {
            return java.util.Optional.empty();
        }
        Instant now = clock.instant();
        boolean published = key.status() == SigningKeyStatus.ACTIVE
                || (key.status() == SigningKeyStatus.RETIRING
                && key.publishUntil().isAfter(now));
        return published ? java.util.Optional.of(signer(key)) : java.util.Optional.empty();
    }

    private RSAKey signer(SigningKeyEntity entity) {
        CachedSigner cached = signerCache.get(entity.id());
        if (cached != null && cached.version() == entity.version()) {
            return cached.key();
        }
        RSAKey loaded = toJwk(entity, true);
        signerCache.put(entity.id(), new CachedSigner(entity.version(), loaded));
        return loaded;
    }

    private RSAKey publicJwk(SigningKeyEntity entity) {
        CachedPublicKey cached = publicKeyCache.get(entity.id());
        if (cached != null && cached.version() == entity.version()) {
            return cached.key();
        }
        RSAKey loaded = toJwk(entity, false);
        publicKeyCache.put(entity.id(), new CachedPublicKey(entity.version(), loaded));
        return loaded;
    }

    private RSAKey toJwk(SigningKeyEntity entity, boolean includePrivateKey) {
        byte[] privateBytes = null;
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(entity.publicKeyX509())
            );
            RSAKey.Builder builder = new RSAKey.Builder(publicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(entity.id());
            if (includePrivateKey) {
                privateBytes = protector.decrypt(
                        entity.id(),
                        entity.protectionKeyVersion(),
                        entity.encryptedPrivateKey(),
                        entity.encryptionNonce()
                );
                RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                        new PKCS8EncodedKeySpec(privateBytes)
                );
                if (!publicKey.getModulus().equals(privateKey.getModulus())) {
                    throw new IllegalStateException("persisted signing key pair is inconsistent");
                }
                builder.privateKey(privateKey);
            }
            return builder.build();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("persisted signing key is invalid", exception);
        } finally {
            if (privateBytes != null) {
                Arrays.fill(privateBytes, (byte) 0);
            }
        }
    }

    private void reencrypt(SigningKeyEntity key, OperatorContext context, Instant now) {
        byte[] plaintext = protector.decrypt(
                key.id(),
                key.protectionKeyVersion(),
                key.encryptedPrivateKey(),
                key.encryptionNonce()
        );
        byte[] verified = null;
        try {
            SigningKeyProtector.EncryptedPrivateKey replacement = protector.encrypt(
                    key.id(),
                    plaintext
            );
            verified = protector.decrypt(
                    key.id(),
                    replacement.version(),
                    replacement.ciphertext(),
                    replacement.nonce()
            );
            if (!MessageDigest.isEqual(plaintext, verified)) {
                throw new IllegalStateException("signing-key re-encryption verification failed");
            }
            key.replaceProtection(
                    replacement.version(),
                    replacement.ciphertext(),
                    replacement.nonce()
            );
            appendEvent(key.id(), SigningKeyEventType.REENCRYPTED, context, now);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (verified != null) {
                Arrays.fill(verified, (byte) 0);
            }
        }
    }

    private SigningKeyEntity saveNewKey(
            String keyId,
            KeyPair pair,
            Instant now,
            SigningKeyEventType eventType,
            OperatorContext context
    ) {
        byte[] privateBytes = pair.getPrivate().getEncoded();
        try {
            SigningKeyProtector.EncryptedPrivateKey encrypted = protector.encrypt(
                    keyId,
                    privateBytes
            );
            SigningKeyEntity created = keys.save(new SigningKeyEntity(
                    keyId,
                    pair.getPublic().getEncoded(),
                    encrypted.ciphertext(),
                    encrypted.nonce(),
                    encrypted.version(),
                    now
            ));
            appendEvent(keyId, eventType, context, now);
            return created;
        } finally {
            Arrays.fill(privateBytes, (byte) 0);
        }
    }

    private void appendEvent(
            String keyId,
            SigningKeyEventType eventType,
            OperatorContext context,
            Instant now
    ) {
        events.save(new SigningKeyEventEntity(
                UUID.randomUUID(),
                keyId,
                eventType,
                context.actorId(),
                context.operationId(),
                now
        ));
    }

    private OperatorOperationEntity retry(
            String type,
            String target,
            OperatorContext context
    ) {
        OperatorOperationEntity existing = operations.findById(context.operationId()).orElse(null);
        if (existing == null) {
            return null;
        }
        if (!type.equals(existing.operationType())
                || !context.actorId().equals(existing.actorId())
                || !Objects.equals(target, existing.targetId())) {
            throw new IllegalArgumentException("operator operation ID is already in use");
        }
        return existing;
    }

    private void complete(
            String type,
            String target,
            String resultKeyId,
            Integer resultCount,
            OperatorContext context,
            Instant now
    ) {
        operations.save(new OperatorOperationEntity(
                context.operationId(),
                type,
                context.actorId(),
                target,
                resultKeyId,
                resultCount,
                now
        ));
    }

    private SigningKeySummary completedKey(OperatorOperationEntity operation) {
        if (operation.resultKeyId() == null) {
            throw new IllegalStateException("stored operator result is invalid");
        }
        return keys.findById(operation.resultKeyId())
                .map(JpaSigningKeyStore::summary)
                .orElseThrow(() -> new IllegalStateException("stored operator result key is missing"));
    }

    private static int completedCount(OperatorOperationEntity operation) {
        if (operation.resultCount() == null) {
            throw new IllegalStateException("stored operator result is invalid");
        }
        return operation.resultCount();
    }

    private void lockOperations() {
        guard.findLockedById(OPERATION_GUARD_ID)
                .orElseThrow(() -> new IllegalStateException("signing-key operation guard is missing"));
    }

    private KeyPair loadBootstrapPair() {
        requireConfigured(
                bootstrapProperties.signingPrivateKeyPkcs8Base64(),
                "IAM_OAUTH_SIGNING_PRIVATE_KEY_PKCS8_BASE64"
        );
        requireConfigured(
                bootstrapProperties.signingPublicKeyX509Base64(),
                "IAM_OAUTH_SIGNING_PUBLIC_KEY_X509_BASE64"
        );
        return RsaKeyLoader.load(
                bootstrapProperties.signingPrivateKeyPkcs8Base64(),
                bootstrapProperties.signingPublicKeyX509Base64()
        );
    }

    private String requireBootstrapKeyId() {
        requireConfigured(bootstrapProperties.signingKeyId(), "IAM_OAUTH_SIGNING_KEY_ID");
        return requireKeyId(bootstrapProperties.signingKeyId());
    }

    private void invalidateAfterCommit() {
        Runnable clear = () -> {
            signerCache.clear();
            publicKeyCache.clear();
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clear.run();
                }
            });
        } else {
            clear.run();
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

    private static String generatedKeyId() {
        return "rsa-" + UUID.randomUUID();
    }

    private static String requireKeyId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("signing-key ID is invalid");
        }
        return value;
    }

    private static void requireConfigured(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for explicit signing-key bootstrap");
        }
    }

    private static SigningKeySummary summary(SigningKeyEntity key) {
        return new SigningKeySummary(
                key.id(),
                key.algorithm(),
                key.status().name(),
                key.activatedAt(),
                key.publishUntil(),
                key.retiredAt(),
                key.compromisedAt()
        );
    }

    private record CachedSigner(long version, RSAKey key) {
    }

    private record CachedPublicKey(long version, RSAKey key) {
    }
}
