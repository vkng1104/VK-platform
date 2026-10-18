package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.api.OAuthClientRegistrationOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.domain.OAuthClientEventType;
import com.vkplatform.iam.session.infrastructure.config.SessionProperties;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientEventEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class JpaOAuthClientRegistrationService implements OAuthClientRegistrationOperations {
    public static final String API_ACCESS_SCOPE = "api.access";
    public static final String AUTHORIZATION_CHECK_SCOPE = "iam.authorization.check";
    public static final String RELATIONSHIP_WRITE_SCOPE = "iam.relationship.write";
    private static final short OPERATION_GUARD_ID = 1;

    private final SpringDataOAuthClientOperationGuardRepository guard;
    private final JpaRegisteredClientRepository clients;
    private final SpringDataOAuthClientEventRepository events;
    private final PasswordEncoder passwordEncoder;
    private final SessionProperties properties;
    private final Clock clock;

    JpaOAuthClientRegistrationService(
            SpringDataOAuthClientOperationGuardRepository guard,
            JpaRegisteredClientRepository clients,
            SpringDataOAuthClientEventRepository events,
            PasswordEncoder passwordEncoder,
            SessionProperties properties,
            Clock clock
    ) {
        this.guard = guard;
        this.clients = clients;
        this.events = events;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int reconcile(OperatorContext context) {
        Objects.requireNonNull(context);
        guard.findLockedById(OPERATION_GUARD_ID)
                .orElseThrow(() -> new IllegalStateException("OAuth client operation guard is missing"));
        int changed = reconcileBff(context);
        return changed + reconcileService(context);
    }

    public boolean requiredClientsReady() {
        RegisteredClient bff = clients.findByClientId(properties.bffClientId());
        RegisteredClient service = clients.findByClientId(properties.serviceClientId());
        return bff != null && service != null
                && sameNonSecretConfiguration(bff, desiredBff(bff))
                && sameNonSecretConfiguration(service, desiredService(service));
    }

    private int reconcileBff(OperatorContext context) {
        RegisteredClient current = clients.findLockedByClientId(properties.bffClientId());
        RegisteredClient desired = desiredBff(current);
        return persistIfNeeded(current, desired, context);
    }

    private int reconcileService(OperatorContext context) {
        RegisteredClient current = clients.findLockedByClientId(properties.serviceClientId());
        RegisteredClient desired = desiredService(current);
        return persistIfNeeded(current, desired, context);
    }

    private int persistIfNeeded(
            RegisteredClient current,
            RegisteredClient desired,
            OperatorContext context
    ) {
        if (current != null && sameNonSecretConfiguration(current, desired)) {
            return 0;
        }
        clients.save(desired);
        OAuthClientEventType type = current == null
                ? OAuthClientEventType.REGISTERED
                : OAuthClientEventType.RECONCILED;
        events.save(new OAuthClientEventEntity(
                UUID.randomUUID(),
                desired.getId(),
                type,
                context.actorId(),
                context.operationId(),
                clock.instant()
        ));
        return 1;
    }

    private RegisteredClient desiredBff(RegisteredClient current) {
        RegisteredClient.Builder builder = base(
                current,
                properties.bffClientId(),
                properties.bffClientSecret(),
                "IAM_BFF_CLIENT_SECRET",
                "VK Platform web BFF"
        )
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(properties.bffRedirectUri().toString())
                .scope(API_ACCESS_SCOPE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .refreshTokenTimeToLive(properties.refreshTokenTtl())
                        .reuseRefreshTokens(false)
                        .build());
        return builder.build();
    }

    private RegisteredClient desiredService(RegisteredClient current) {
        return base(
                current,
                properties.serviceClientId(),
                properties.serviceClientSecret(),
                "IAM_SERVICE_CLIENT_SECRET",
                "VK Platform API service"
        )
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope(AUTHORIZATION_CHECK_SCOPE)
                .scope(RELATIONSHIP_WRITE_SCOPE)
                .clientSettings(ClientSettings.builder().build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .build())
                .build();
    }

    private RegisteredClient.Builder base(
            RegisteredClient current,
            String clientId,
            String bootstrapSecret,
            String secretName,
            String clientName
    ) {
        String secret = current == null ? encodedRequiredSecret(bootstrapSecret, secretName) : current.getClientSecret();
        return RegisteredClient.withId(current == null ? UUID.randomUUID().toString() : current.getId())
                .clientId(clientId)
                .clientIdIssuedAt(current == null ? clock.instant() : current.getClientIdIssuedAt())
                .clientSecret(secret)
                .clientSecretExpiresAt(current == null ? null : current.getClientSecretExpiresAt())
                .clientName(clientName)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
    }

    private String encodedRequiredSecret(String secret, String name) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(name + " must contain at least 32 characters for initial registration");
        }
        return passwordEncoder.encode(secret);
    }

    private static boolean sameNonSecretConfiguration(RegisteredClient left, RegisteredClient right) {
        return left.getClientId().equals(right.getClientId())
                && left.getClientName().equals(right.getClientName())
                && left.getClientAuthenticationMethods().equals(right.getClientAuthenticationMethods())
                && left.getAuthorizationGrantTypes().equals(right.getAuthorizationGrantTypes())
                && left.getRedirectUris().equals(right.getRedirectUris())
                && left.getPostLogoutRedirectUris().equals(right.getPostLogoutRedirectUris())
                && left.getScopes().equals(right.getScopes())
                && left.getClientSettings().getSettings().equals(right.getClientSettings().getSettings())
                && left.getTokenSettings().getSettings().equals(right.getTokenSettings().getSettings());
    }
}
