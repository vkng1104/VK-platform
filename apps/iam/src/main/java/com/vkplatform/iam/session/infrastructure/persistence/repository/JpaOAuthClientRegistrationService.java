package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.api.OAuthClientReadiness;
import com.vkplatform.iam.session.api.OAuthClientRegistrationOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.domain.OAuthClientEventType;
import com.vkplatform.iam.session.infrastructure.config.OAuthAuthorizationProperties;
import com.vkplatform.iam.session.infrastructure.config.OAuthClientProperties;
import com.vkplatform.iam.session.infrastructure.config.SessionProperties;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientEventEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OperatorOperationEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "iam.oauth.enabled", havingValue = "true")
public class JpaOAuthClientRegistrationService
        implements OAuthClientRegistrationOperations, OAuthClientReadiness {
    public static final String API_ACCESS_SCOPE = "api.access";
    private static final short OPERATION_GUARD_ID = 1;
    private static final String RECONCILE = "reconcile-oauth-clients";

    private final SpringDataOAuthClientOperationGuardRepository guard;
    private final JpaRegisteredClientRepository clients;
    private final SpringDataOAuthClientEventRepository events;
    private final SpringDataOperatorOperationRepository operations;
    private final PasswordEncoder passwordEncoder;
    private final SessionProperties sessions;
    private final OAuthAuthorizationProperties authorization;
    private final OAuthClientProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    JpaOAuthClientRegistrationService(
            SpringDataOAuthClientOperationGuardRepository guard,
            JpaRegisteredClientRepository clients,
            SpringDataOAuthClientEventRepository events,
            SpringDataOperatorOperationRepository operations,
            PasswordEncoder passwordEncoder,
            SessionProperties sessions,
            OAuthAuthorizationProperties authorization,
            OAuthClientProperties properties,
            JdbcTemplate jdbcTemplate,
            Clock clock
    ) {
        this.guard = guard;
        this.clients = clients;
        this.events = events;
        this.operations = operations;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.authorization = authorization;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int reconcile(OperatorContext context) {
        Objects.requireNonNull(context);
        lockOperations();
        OperatorOperationEntity previous = operations.findById(context.operationId()).orElse(null);
        if (previous != null) {
            validateRetry(previous, context);
            if (previous.resultCount() == null) {
                throw new IllegalStateException("stored operator result is invalid");
            }
            return previous.resultCount();
        }
        Instant now = clock.instant();
        int changed = reconcileBff(context, now) + reconcileService(context, now);
        clients.flush();
        jdbcTemplate.execute("""
                ALTER TABLE iam_session.oauth_authorizations
                VALIDATE CONSTRAINT oauth_authorizations_registered_client_fk
                """);
        operations.save(new OperatorOperationEntity(
                context.operationId(),
                RECONCILE,
                context.actorId(),
                null,
                null,
                changed,
                now
        ));
        return changed;
    }

    @Override
    @Transactional(readOnly = true)
    public Readiness readiness() {
        try {
            RegisteredClient bff = clients.findByClientId(sessions.bffClientId());
            RegisteredClient service = clients.findByClientId(properties.serviceClientId());
            if (bff == null || service == null) {
                return new Readiness(false, "required_oauth_client_missing");
            }
            if (!sameNonSecretConfiguration(bff, desiredBff(bff))
                    || !sameNonSecretConfiguration(service, desiredService(service))) {
                return new Readiness(false, "oauth_client_configuration_drift");
            }
            Instant now = clock.instant();
            if (!hasCurrentSecret(bff, now) || !hasCurrentSecret(service, now)) {
                return new Readiness(false, "oauth_client_secret_missing_or_expired");
            }
            return new Readiness(true, null);
        } catch (RuntimeException exception) {
            return new Readiness(false, "oauth_client_state_unavailable");
        }
    }

    private int reconcileBff(OperatorContext context, Instant now) {
        RegisteredClient current = clients.findLockedByClientId(sessions.bffClientId());
        return persistIfNeeded(current, desiredBff(current), context, now);
    }

    private int reconcileService(OperatorContext context, Instant now) {
        RegisteredClient current = clients.findLockedByClientId(properties.serviceClientId());
        return persistIfNeeded(current, desiredService(current), context, now);
    }

    private int persistIfNeeded(
            RegisteredClient current,
            RegisteredClient desired,
            OperatorContext context,
            Instant now
    ) {
        boolean secretMissing = current != null && current.getClientSecret() == null;
        if (current != null && !secretMissing && sameNonSecretConfiguration(current, desired)) {
            return 0;
        }
        clients.save(desired);
        events.save(new OAuthClientEventEntity(
                UUID.randomUUID(),
                desired.getId(),
                current == null ? OAuthClientEventType.REGISTERED : OAuthClientEventType.RECONCILED,
                context.actorId(),
                context.operationId(),
                now
        ));
        return 1;
    }

    private RegisteredClient desiredBff(RegisteredClient current) {
        return base(
                current,
                "bff:" + sessions.bffClientId(),
                sessions.bffClientId(),
                sessions.bffClientSecret(),
                "IAM_BFF_CLIENT_SECRET",
                "VK Platform web BFF"
        )
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(authorization.bffRedirectUri().toString())
                .scope(API_ACCESS_SCOPE)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .authorizationCodeTimeToLive(authorization.authorizationCodeTtl())
                        .accessTokenTimeToLive(authorization.accessTokenTtl())
                        .refreshTokenTimeToLive(authorization.refreshTokenTtl())
                        .reuseRefreshTokens(false)
                        .build())
                .build();
    }

    private RegisteredClient desiredService(RegisteredClient current) {
        RegisteredClient.Builder builder = base(
                current,
                "service:" + properties.serviceClientId(),
                properties.serviceClientId(),
                properties.serviceBootstrapSecret(),
                "IAM_SERVICE_CLIENT_SECRET",
                "VK Platform API service"
        )
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientSettings(ClientSettings.builder().build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(authorization.accessTokenTtl())
                        .build());
        properties.parsedServiceScopes().forEach(builder::scope);
        return builder.build();
    }

    private RegisteredClient.Builder base(
            RegisteredClient current,
            String stableId,
            String clientId,
            String bootstrapSecret,
            String secretName,
            String clientName
    ) {
        if (current != null && !stableId.equals(current.getId())) {
            throw new IllegalStateException("OAuth client internal ID is incompatible");
        }
        String secret = current == null || current.getClientSecret() == null
                ? encodedRequiredSecret(bootstrapSecret, secretName)
                : current.getClientSecret();
        return RegisteredClient.withId(stableId)
                .clientId(clientId)
                .clientIdIssuedAt(current == null ? clock.instant() : current.getClientIdIssuedAt())
                .clientSecret(secret)
                .clientSecretExpiresAt(current == null ? null : current.getClientSecretExpiresAt())
                .clientName(clientName)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
    }

    private String encodedRequiredSecret(String secret, String name) {
        if (secret == null || secret.length() < 32 || secret.length() > 256) {
            throw new IllegalStateException(
                    name + " must contain between 32 and 256 characters for initial registration"
            );
        }
        return passwordEncoder.encode(secret);
    }

    private void lockOperations() {
        guard.findLockedById(OPERATION_GUARD_ID)
                .orElseThrow(() -> new IllegalStateException("OAuth client operation guard is missing"));
    }

    private static void validateRetry(
            OperatorOperationEntity previous,
            OperatorContext context
    ) {
        if (!RECONCILE.equals(previous.operationType())
                || !context.actorId().equals(previous.actorId())
                || previous.targetId() != null) {
            throw new IllegalArgumentException("operator operation ID is already in use");
        }
    }

    private static boolean hasCurrentSecret(RegisteredClient client, Instant now) {
        return client.getClientSecret() != null
                && (client.getClientSecretExpiresAt() == null
                || client.getClientSecretExpiresAt().isAfter(now));
    }

    private static boolean sameNonSecretConfiguration(
            RegisteredClient left,
            RegisteredClient right
    ) {
        return left.getId().equals(right.getId())
                && left.getClientId().equals(right.getClientId())
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
