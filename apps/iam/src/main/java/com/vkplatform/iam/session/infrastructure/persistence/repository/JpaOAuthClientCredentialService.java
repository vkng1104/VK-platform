package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.api.OAuthClientOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import com.vkplatform.iam.session.api.result.OAuthClientSecretRotation;
import com.vkplatform.iam.session.domain.OAuthClientEventType;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientEventEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OperatorOperationEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "iam.oauth.enabled", havingValue = "true")
public class JpaOAuthClientCredentialService implements OAuthClientOperations {
    private static final short OPERATION_GUARD_ID = 1;
    private static final String ROTATE = "rotate-oauth-client-secret";

    private final SpringDataOAuthClientOperationGuardRepository guard;
    private final JpaRegisteredClientRepository clients;
    private final SpringDataOAuthClientEventRepository events;
    private final SpringDataOperatorOperationRepository operations;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    JpaOAuthClientCredentialService(
            SpringDataOAuthClientOperationGuardRepository guard,
            JpaRegisteredClientRepository clients,
            SpringDataOAuthClientEventRepository events,
            SpringDataOperatorOperationRepository operations,
            PasswordEncoder passwordEncoder,
            Clock clock
    ) {
        this.guard = guard;
        this.clients = clients;
        this.events = events;
        this.operations = operations;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Override
    @Transactional
    public OAuthClientSecretRotation rotateSecret(RotateOAuthClientSecret command) {
        validate(command);
        String clientId = command.clientId().strip();
        byte[] fingerprint = fingerprint(clientId, command.newSecret(), command.expiresAt());
        lockOperations();
        OperatorOperationEntity previous = operations.findById(
                command.operator().operationId()
        ).orElse(null);
        if (previous != null) {
            validateRetry(previous, command.operator(), clientId, fingerprint);
            return new OAuthClientSecretRotation(
                    clientId,
                    previous.completedAt(),
                    previous.resultTimestamp()
            );
        }
        Instant now = clock.instant();
        if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("OAuth client credential expiry must be in the future");
        }
        RegisteredClient current = clients.findLockedByClientId(clientId);
        if (current == null) {
            throw new IllegalArgumentException("OAuth client was not found");
        }
        RegisteredClient changed = RegisteredClient.from(current)
                .clientSecret(passwordEncoder.encode(command.newSecret()))
                .clientSecretExpiresAt(command.expiresAt())
                .build();
        clients.save(changed);
        events.save(new OAuthClientEventEntity(
                UUID.randomUUID(),
                changed.getId(),
                OAuthClientEventType.CREDENTIAL_ROTATED,
                command.operator().actorId(),
                command.operator().operationId(),
                now
        ));
        operations.save(new OperatorOperationEntity(
                command.operator().operationId(),
                ROTATE,
                command.operator().actorId(),
                clientId,
                null,
                null,
                fingerprint,
                command.expiresAt(),
                now
        ));
        return new OAuthClientSecretRotation(clientId, now, command.expiresAt());
    }

    private void lockOperations() {
        guard.findLockedById(OPERATION_GUARD_ID)
                .orElseThrow(() -> new IllegalStateException("OAuth client operation guard is missing"));
    }

    private static void validate(RotateOAuthClientSecret command) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(command.operator());
        if (command.clientId() == null
                || !command.clientId().matches("[A-Za-z0-9._:-]{1,128}")
                || command.newSecret() == null
                || command.newSecret().length() < 32
                || command.newSecret().length() > 256) {
            throw new IllegalArgumentException("OAuth client credential rotation is invalid");
        }
    }

    private static void validateRetry(
            OperatorOperationEntity previous,
            OperatorContext context,
            String clientId,
            byte[] fingerprint
    ) {
        if (!ROTATE.equals(previous.operationType())
                || !context.actorId().equals(previous.actorId())
                || !clientId.equals(previous.targetId())
                || !MessageDigest.isEqual(fingerprint, previous.requestFingerprint())) {
            throw new IllegalArgumentException("operator operation ID is already in use");
        }
    }

    private static byte[] fingerprint(String clientId, String secret, Instant expiresAt) {
        byte[] material = (clientId + "\0" + secret + "\0" + Objects.toString(expiresAt, ""))
                .getBytes(StandardCharsets.UTF_8);
        try {
            return MessageDigest.getInstance("SHA-256").digest(material);
        } catch (Exception exception) {
            throw new IllegalStateException("OAuth client operation cannot be fingerprinted", exception);
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }
}
