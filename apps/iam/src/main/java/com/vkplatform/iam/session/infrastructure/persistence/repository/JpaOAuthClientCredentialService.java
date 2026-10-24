package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.api.OAuthClientOperations;
import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import com.vkplatform.iam.session.api.result.OAuthClientSecretRotation;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientEventEntity;
import com.vkplatform.iam.session.domain.OAuthClientEventType;
import com.vkplatform.iam.session.infrastructure.config.SessionSecurityMetrics;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class JpaOAuthClientCredentialService implements OAuthClientOperations {
    private final JpaRegisteredClientRepository clients;
    private final SpringDataOAuthClientEventRepository events;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SessionSecurityMetrics metrics;

    JpaOAuthClientCredentialService(
            JpaRegisteredClientRepository clients,
            SpringDataOAuthClientEventRepository events,
            PasswordEncoder passwordEncoder,
            Clock clock,
            SessionSecurityMetrics metrics
    ) {
        this.clients = clients;
        this.events = events;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public OAuthClientSecretRotation rotateSecret(RotateOAuthClientSecret command) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(command.operator());
        if (command.clientId() == null || command.clientId().isBlank()
                || command.newSecret() == null || command.newSecret().length() < 32) {
            throw new IllegalArgumentException("OAuth client credential rotation is invalid");
        }
        Instant now = clock.instant();
        if (command.expiresAt() != null && !command.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("OAuth client credential expiry must be in the future");
        }
        RegisteredClient current = clients.findLockedByClientId(command.clientId().strip());
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
        metrics.clientCredentialRotated();
        return new OAuthClientSecretRotation(changed.getClientId(), now, changed.getClientSecretExpiresAt());
    }
}
