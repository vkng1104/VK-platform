package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.SecurityAuditEventType;
import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.identity.api.command.RecordSecurityAuditEvent;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.infrastructure.crypto.TokenDigest;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthRefreshTokenEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Repository
@ConditionalOnProperty(
        name = {"iam.oauth.enabled", "iam.session.enabled"},
        havingValue = "true"
)
public class JpaOAuth2AuthorizationService implements OAuth2AuthorizationService {
    private static final String SESSION_ID_DETAIL = "session_id";

    private final SpringDataOAuthAuthorizationRepository authorizations;
    private final SpringDataOAuthRefreshTokenRepository refreshTokens;
    private final SpringDataAuthenticationSessionRepository sessions;
    private final PasswordlessIdentityOperations identities;
    private final SecurityAuditOperations audit;
    private final IamSecurityTelemetry telemetry;
    private final RegisteredClientRepository clients;
    private final Clock clock;
    private final OAuthJsonCodec json = new OAuthJsonCodec();

    JpaOAuth2AuthorizationService(
            SpringDataOAuthAuthorizationRepository authorizations,
            SpringDataOAuthRefreshTokenRepository refreshTokens,
            SpringDataAuthenticationSessionRepository sessions,
            PasswordlessIdentityOperations identities,
            SecurityAuditOperations audit,
            IamSecurityTelemetry telemetry,
            RegisteredClientRepository clients,
            Clock clock
    ) {
        this.authorizations = authorizations;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.identities = identities;
        this.audit = audit;
        this.telemetry = telemetry;
        this.clients = clients;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void save(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        OAuthAuthorizationEntity existing = authorizations.findById(authorization.getId()).orElse(null);
        UUID requestedSessionId = sessionId(authorization);
        UUID persistedSessionId = existing == null ? null : existing.sessionId();
        UUID effectiveSessionId = requestedSessionId == null ? persistedSessionId : requestedSessionId;
        if (AuthorizationGrantType.CLIENT_CREDENTIALS.equals(
                authorization.getAuthorizationGrantType()
        )) {
            validateClientCredentialsAuthorization(authorization, effectiveSessionId);
            effectiveSessionId = null;
        } else {
            lockUsableSession(
                    effectiveSessionId,
                    authorization.getPrincipalName(),
                    authorization.getRegisteredClientId()
            );
        }
        OAuthAuthorizationEntity entity = authorizations.findLockedById(authorization.getId())
                .orElseGet(OAuthAuthorizationEntity::new);
        if (entity.invalidatedAt() != null) {
            throw new IllegalStateException("invalidated OAuth authorization cannot be changed");
        }
        byte[] previousRefreshDigest = entity.refreshTokenDigest();
        byte[] previousAccessDigest = entity.accessTokenDigest();
        mapAuthorization(authorization, effectiveSessionId, entity);
        authorizations.saveAndFlush(entity);
        rotateRefreshHistory(entity, previousRefreshDigest);
        if (entity.accessTokenDigest() != null
                && !TokenDigest.matches(previousAccessDigest, entity.accessTokenDigest())) {
            telemetry.recordAfterCommit(
                    IamSecurityTelemetry.Operation.TOKEN_ISSUE,
                    IamSecurityTelemetry.Outcome.SUCCESS,
                    telemetry.start()
            );
        }
    }

    @Override
    @Transactional
    public void remove(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        OAuthAuthorizationEntity candidate = authorizations.findById(authorization.getId()).orElse(null);
        if (candidate == null) {
            return;
        }
        if (!isClientCredentials(candidate)) {
            lockUsableSession(candidate);
        }
        authorizations.findLockedById(authorization.getId()).ifPresent(entity -> entity.invalidate(
                clock.instant(),
                OAuthAuthorizationInvalidationReason.AUTHORIZATION_REMOVED
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2Authorization findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return authorizations.findById(id)
                .filter(this::isUsable)
                .map(entity -> toObject(entity, null))
                .orElse(null);
    }

    @Override
    @Transactional
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        Assert.hasText(token, "token cannot be empty");
        byte[] digest = TokenDigest.sha256(token);
        if (tokenType == null) {
            OAuthAuthorizationEntity state = authorizations.findByStateDigest(digest).orElse(null);
            if (state != null) {
                return usable(state, new PresentedToken(null, token, digest));
            }
            OAuthAuthorizationEntity code = authorizations.findByAuthorizationCodeDigest(digest).orElse(null);
            if (code != null) {
                return usable(lockAuthorizationForSession(code), new PresentedToken(null, token, digest));
            }
            OAuthAuthorizationEntity access = authorizations.findByAccessTokenDigest(digest).orElse(null);
            if (access != null) {
                return usable(access, new PresentedToken(null, token, digest));
            }
            return findByRefreshToken(token, digest);
        }

        String type = tokenType.getValue();
        if (OAuth2ParameterNames.STATE.equals(type)) {
            return authorizations.findByStateDigest(digest)
                    .map(entity -> usable(entity, new PresentedToken(type, token, digest)))
                    .orElse(null);
        }
        if (OAuth2ParameterNames.CODE.equals(type)) {
            long startedAt = telemetry.start();
            OAuth2Authorization result = authorizations.findByAuthorizationCodeDigest(digest)
                    .map(this::lockAuthorizationForSession)
                    .map(entity -> usable(entity, new PresentedToken(type, token, digest)))
                    .orElse(null);
            telemetry.record(
                    IamSecurityTelemetry.Operation.AUTHORIZATION_CODE_LOOKUP,
                    result == null
                            ? IamSecurityTelemetry.Outcome.NOT_FOUND
                            : IamSecurityTelemetry.Outcome.SUCCESS,
                    startedAt
            );
            return result;
        }
        if (OAuth2ParameterNames.ACCESS_TOKEN.equals(type)) {
            return authorizations.findByAccessTokenDigest(digest)
                    .map(entity -> usable(entity, new PresentedToken(type, token, digest)))
                    .orElse(null);
        }
        if (OAuth2ParameterNames.REFRESH_TOKEN.equals(type)) {
            return findByRefreshToken(token, digest);
        }
        return null;
    }

    private OAuth2Authorization findByRefreshToken(String rawToken, byte[] digest) {
        String authorizationId = refreshTokens.findAuthorizationIdByTokenDigest(digest).orElse(null);
        if (authorizationId == null) {
            return null;
        }
        OAuthAuthorizationEntity candidate = authorizations.findById(authorizationId).orElse(null);
        if (candidate == null || !lockUsableSession(candidate)) {
            return null;
        }
        OAuthAuthorizationEntity authorization = authorizations.findLockedById(authorizationId).orElse(null);
        OAuthRefreshTokenEntity refresh = refreshTokens.findLockedByTokenDigest(digest).orElse(null);
        Instant now = clock.instant();
        if (authorization == null || refresh == null || authorization.invalidatedAt() != null) {
            return null;
        }
        if (refresh.consumedAt() != null) {
            compromise(refresh, authorization, now);
            return null;
        }
        if (!refresh.isActive(now) || !TokenDigest.matches(authorization.refreshTokenDigest(), digest)) {
            return null;
        }
        return toObject(
                authorization,
                new PresentedToken(OAuth2ParameterNames.REFRESH_TOKEN, rawToken, digest)
        );
    }

    private com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity
            lockAuthorizationForSession(OAuthAuthorizationEntity candidate) {
        if (candidate == null || !lockUsableSession(candidate)) {
            return null;
        }
        return authorizations.findLockedById(candidate.getId()).orElse(null);
    }

    private boolean lockUsableSession(OAuthAuthorizationEntity authorization) {
        if (isClientCredentials(authorization)) {
            return authorization.invalidatedAt() == null
                    && clients.findById(authorization.registeredClientId()) != null;
        }
        try {
            lockUsableSession(
                    authorization.sessionId(),
                    authorization.principalName(),
                    authorization.registeredClientId()
            );
            return authorization.invalidatedAt() == null;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private SessionEntity lockUsableSession(
            UUID sessionId,
            String principalName,
            String registeredClientId
    ) {
        if (sessionId == null) {
            throw new IllegalStateException("OAuth browser session is missing");
        }
        UUID accountId = accountId(principalName);
        identities.activeAccount(accountId);
        SessionEntity entity = sessions.findLockedById(sessionId)
                .orElseThrow(() -> new IllegalStateException("OAuth browser session is unavailable"));
        AuthenticationSession session = entity.toDomain();
        if (!session.canAuthorize(accountId, clientId(registeredClientId), clock.instant())) {
            throw new IllegalStateException("OAuth browser session is unavailable");
        }
        return entity;
    }

    private boolean isUsable(OAuthAuthorizationEntity entity) {
        if (entity.invalidatedAt() != null) {
            return false;
        }
        if (isClientCredentials(entity)) {
            return clients.findById(entity.registeredClientId()) != null;
        }
        return sessions.findById(entity.sessionId())
                .map(SessionEntity::toDomain)
                .filter(session -> session.canAuthorize(
                        accountId(entity.principalName()),
                        clientId(entity.registeredClientId()),
                        clock.instant()
                ))
                .isPresent();
    }

    private OAuth2Authorization usable(OAuthAuthorizationEntity entity, PresentedToken presented) {
        return entity != null && isUsable(entity) ? toObject(entity, presented) : null;
    }

    private void compromise(
            OAuthRefreshTokenEntity refresh,
            OAuthAuthorizationEntity authorization,
            Instant now
    ) {
        long startedAt = telemetry.start();
        boolean firstReuse = refresh.reuseDetectedAt() == null;
        refresh.detectReuse(now);
        authorization.invalidate(now, OAuthAuthorizationInvalidationReason.REFRESH_TOKEN_REUSED);
        sessions.findLockedById(authorization.sessionId())
                .ifPresent(session -> session.apply(session.toDomain().compromise(now)));
        if (firstReuse) {
            audit.record(new RecordSecurityAuditEvent(
                    SecurityAuditEventType.REFRESH_TOKEN_REUSED,
                    accountId(authorization.principalName()),
                    authorization.sessionId(),
                    authorization.registeredClientId(),
                    null,
                    now
            ));
            telemetry.recordAfterCommit(
                    IamSecurityTelemetry.Operation.REFRESH_REPLAY,
                    IamSecurityTelemetry.Outcome.REPLAY,
                    startedAt
            );
        }
    }

    private void rotateRefreshHistory(OAuthAuthorizationEntity entity, byte[] previousDigest) {
        byte[] currentDigest = entity.refreshTokenDigest();
        if (currentDigest == null || TokenDigest.matches(previousDigest, currentDigest)) {
            return;
        }
        UUID nextId = UUID.randomUUID();
        if (previousDigest == null) {
            refreshTokens.save(new OAuthRefreshTokenEntity(
                    nextId,
                    entity.getId(),
                    nextId,
                    null,
                    currentDigest,
                    entity.refreshTokenIssuedAt(),
                    entity.refreshTokenExpiresAt()
            ));
            return;
        }

        long startedAt = telemetry.start();

        OAuthRefreshTokenEntity previous = refreshTokens.findLockedByTokenDigest(previousDigest)
                .orElseThrow(() -> new DataRetrievalFailureException("OAuth refresh lineage is missing"));
        Instant now = clock.instant();
        previous.consume(nextId, now);
        refreshTokens.save(new OAuthRefreshTokenEntity(
                nextId,
                entity.getId(),
                previous.familyId(),
                previous.getId(),
                currentDigest,
                entity.refreshTokenIssuedAt(),
                entity.refreshTokenExpiresAt()
        ));
        sessions.findLockedById(entity.sessionId())
                .ifPresent(session -> session.apply(session.toDomain().touch(now)));
        audit.record(new RecordSecurityAuditEvent(
                SecurityAuditEventType.REFRESH_TOKEN_ROTATED,
                accountId(entity.principalName()),
                entity.sessionId(),
                entity.registeredClientId(),
                null,
                now
        ));
        telemetry.recordAfterCommit(
                IamSecurityTelemetry.Operation.REFRESH_ROTATION,
                IamSecurityTelemetry.Outcome.SUCCESS,
                startedAt
        );
    }

    private void validateClientCredentialsAuthorization(
            OAuth2Authorization authorization,
            UUID sessionId
    ) {
        if (sessionId != null) {
            throw new IllegalStateException("service OAuth authorization cannot reference a user session");
        }
        RegisteredClient client = clients.findById(authorization.getRegisteredClientId());
        if (client == null
                || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.CLIENT_CREDENTIALS)
                || !client.getClientId().equals(authorization.getPrincipalName())) {
            throw new IllegalStateException("service OAuth authorization is invalid");
        }
    }

    private static boolean isClientCredentials(OAuthAuthorizationEntity authorization) {
        return AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()
                .equals(authorization.authorizationGrantType());
    }

    private void mapAuthorization(
            OAuth2Authorization authorization,
            UUID sessionId,
            OAuthAuthorizationEntity entity
    ) {
        entity.id(authorization.getId());
        entity.registeredClientId(authorization.getRegisteredClientId());
        entity.principalName(authorization.getPrincipalName());
        entity.sessionId(sessionId);
        entity.authorizationGrantType(authorization.getAuthorizationGrantType().getValue());
        entity.authorizedScopes(StringUtils.collectionToCommaDelimitedString(authorization.getAuthorizedScopes()));

        String state = authorization.getAttribute(OAuth2ParameterNames.STATE);
        entity.stateDigest(state == null ? null : TokenDigest.digestOrRestore(state));
        entity.attributesJson(json.writeMap(attributesWithProtectedState(
                authorization.getAttributes(),
                tokenValue(entity.stateDigest(), OAuth2ParameterNames.STATE, null)
        )));
        setToken(
                authorization.getToken(OAuth2AuthorizationCode.class),
                entity::authorizationCodeDigest,
                entity::authorizationCodeIssuedAt,
                entity::authorizationCodeExpiresAt,
                entity::authorizationCodeMetadataJson
        );
        OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getAccessToken();
        setToken(
                access,
                entity::accessTokenDigest,
                entity::accessTokenIssuedAt,
                entity::accessTokenExpiresAt,
                entity::accessTokenMetadataJson
        );
        if (access == null) {
            entity.accessTokenType(null);
            entity.accessTokenScopes(null);
        } else {
            entity.accessTokenType(access.getToken().getTokenType().getValue());
            entity.accessTokenScopes(StringUtils.collectionToCommaDelimitedString(
                    access.getToken().getScopes()
            ));
        }
        setToken(
                authorization.getRefreshToken(),
                entity::refreshTokenDigest,
                entity::refreshTokenIssuedAt,
                entity::refreshTokenExpiresAt,
                entity::refreshTokenMetadataJson
        );
    }

    private void setToken(
            OAuth2Authorization.Token<?> token,
            Consumer<byte[]> digest,
            Consumer<Instant> issuedAt,
            Consumer<Instant> expiresAt,
            Consumer<String> metadata
    ) {
        if (token == null) {
            digest.accept(null);
            issuedAt.accept(null);
            expiresAt.accept(null);
            metadata.accept(null);
            return;
        }
        OAuth2Token value = token.getToken();
        digest.accept(TokenDigest.digestOrRestore(value.getTokenValue()));
        issuedAt.accept(value.getIssuedAt());
        expiresAt.accept(value.getExpiresAt());
        metadata.accept(json.writeMap(token.getMetadata()));
    }

    private OAuth2Authorization toObject(OAuthAuthorizationEntity entity, PresentedToken presented) {
        RegisteredClient client = clients.findById(entity.registeredClientId());
        if (client == null) {
            throw new DataRetrievalFailureException("OAuth registered client is missing");
        }
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(client)
                .id(entity.getId())
                .principalName(entity.principalName())
                .authorizationGrantType(grantType(entity.authorizationGrantType()))
                .authorizedScopes(values(entity.authorizedScopes()))
                .attributes(attributes -> attributes.putAll(attributesWithProtectedState(
                        json.readMap(entity.attributesJson()),
                        tokenValue(entity.stateDigest(), OAuth2ParameterNames.STATE, presented)
                )));
        if (entity.authorizationCodeDigest() != null) {
            OAuth2AuthorizationCode code = new OAuth2AuthorizationCode(
                    tokenValue(entity.authorizationCodeDigest(), OAuth2ParameterNames.CODE, presented),
                    entity.authorizationCodeIssuedAt(),
                    entity.authorizationCodeExpiresAt()
            );
            builder.token(code, metadata -> metadata.putAll(json.readMap(
                    entity.authorizationCodeMetadataJson()
            )));
        }
        if (entity.accessTokenDigest() != null) {
            OAuth2AccessToken access = new OAuth2AccessToken(
                    accessTokenType(entity.accessTokenType()),
                    tokenValue(entity.accessTokenDigest(), OAuth2ParameterNames.ACCESS_TOKEN, presented),
                    entity.accessTokenIssuedAt(),
                    entity.accessTokenExpiresAt(),
                    values(entity.accessTokenScopes())
            );
            builder.token(access, metadata -> metadata.putAll(json.readMap(
                    entity.accessTokenMetadataJson()
            )));
        }
        if (entity.refreshTokenDigest() != null) {
            OAuth2RefreshToken refresh = new OAuth2RefreshToken(
                    tokenValue(entity.refreshTokenDigest(), OAuth2ParameterNames.REFRESH_TOKEN, presented),
                    entity.refreshTokenIssuedAt(),
                    entity.refreshTokenExpiresAt()
            );
            builder.token(refresh, metadata -> metadata.putAll(json.readMap(
                    entity.refreshTokenMetadataJson()
            )));
        }
        return builder.build();
    }

    private String clientId(String registeredClientId) {
        RegisteredClient client = clients.findById(registeredClientId);
        if (client == null) {
            throw new DataRetrievalFailureException("OAuth registered client is missing");
        }
        return client.getClientId();
    }

    private static UUID sessionId(OAuth2Authorization authorization) {
        Authentication principal = authorization.getAttribute(Principal.class.getName());
        if (principal == null || !(principal.getDetails() instanceof Map<?, ?> details)) {
            return null;
        }
        Object value = details.get(SESSION_ID_DETAIL);
        try {
            return value == null ? null : UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("authenticated session id is invalid", exception);
        }
    }

    private static UUID accountId(String principalName) {
        try {
            return UUID.fromString(principalName);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("authenticated account id is invalid", exception);
        }
    }

    private static String tokenValue(byte[] digest, String type, PresentedToken presented) {
        if (digest == null) {
            return null;
        }
        if (presented != null
                && (presented.type() == null || type.equals(presented.type()))
                && TokenDigest.matches(digest, presented.digest())) {
            return presented.value();
        }
        return TokenDigest.placeholder(digest);
    }

    private static Map<String, Object> attributesWithProtectedState(
            Map<String, Object> source,
            String state
    ) {
        Map<String, Object> protectedAttributes = new HashMap<>(source);
        if (state == null) {
            protectedAttributes.remove(OAuth2ParameterNames.STATE);
            return protectedAttributes;
        }
        protectedAttributes.put(OAuth2ParameterNames.STATE, state);
        String requestAttribute = OAuth2AuthorizationRequest.class.getName();
        if (protectedAttributes.get(requestAttribute) instanceof OAuth2AuthorizationRequest request) {
            protectedAttributes.put(requestAttribute, authorizationRequestWithState(request, state));
        }
        return protectedAttributes;
    }

    private static OAuth2AuthorizationRequest authorizationRequestWithState(
            OAuth2AuthorizationRequest request,
            String state
    ) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(request.getAuthorizationUri())
                .clientId(request.getClientId())
                .redirectUri(request.getRedirectUri())
                .scopes(request.getScopes())
                .state(state)
                .additionalParameters(parameters -> parameters.putAll(request.getAdditionalParameters()))
                .attributes(attributes -> attributes.putAll(request.getAttributes()))
                .build();
    }

    private static Set<String> values(String value) {
        return value == null ? Set.of() : StringUtils.commaDelimitedListToSet(value);
    }

    private static AuthorizationGrantType grantType(String value) {
        if (AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(value)) {
            return AuthorizationGrantType.AUTHORIZATION_CODE;
        }
        if (AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(value)) {
            return AuthorizationGrantType.REFRESH_TOKEN;
        }
        return new AuthorizationGrantType(value);
    }

    private static OAuth2AccessToken.TokenType accessTokenType(String value) {
        if (OAuth2AccessToken.TokenType.BEARER.getValue().equals(value)) {
            return OAuth2AccessToken.TokenType.BEARER;
        }
        if (OAuth2AccessToken.TokenType.DPOP.getValue().equals(value)) {
            return OAuth2AccessToken.TokenType.DPOP;
        }
        return new OAuth2AccessToken.TokenType(value);
    }

    private record PresentedToken(String type, String value, byte[] digest) {
    }
}
