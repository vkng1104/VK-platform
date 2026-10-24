package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.identity.api.event.SecurityAuditEvent;
import com.vkplatform.iam.identity.api.event.SecurityAuditType;
import com.vkplatform.iam.session.infrastructure.config.SessionSecurityMetrics;
import com.vkplatform.iam.session.domain.SessionStatus;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.infrastructure.crypto.TokenDigest;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthRefreshTokenEntity;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Repository
public class JpaOAuth2AuthorizationService implements OAuth2AuthorizationService {
    private static final String SESSION_ID_DETAIL = "session_id";

    private final SpringDataOAuthAuthorizationRepository authorizations;
    private final SpringDataOAuthRefreshTokenRepository refreshTokens;
    private final SpringDataSessionRepository sessions;
    private final SecurityAuditOperations audit;
    private final RegisteredClientRepository clients;
    private final Clock clock;
    private final SessionSecurityMetrics metrics;
    private final OAuthJsonCodec json = new OAuthJsonCodec();

    JpaOAuth2AuthorizationService(
            SpringDataOAuthAuthorizationRepository authorizations,
            SpringDataOAuthRefreshTokenRepository refreshTokens,
            SpringDataSessionRepository sessions,
            SecurityAuditOperations audit,
            RegisteredClientRepository clients,
            Clock clock,
            SessionSecurityMetrics metrics
    ) {
        this.authorizations = authorizations;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.audit = audit;
        this.clients = clients;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public void save(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        UUID requestedSessionId = sessionId(authorization);
        UUID persistedSessionId = authorizations.findById(authorization.getId())
                .map(OAuthAuthorizationEntity::sessionId)
                .orElse(null);
        if (!lockUsableSession(requestedSessionId == null ? persistedSessionId : requestedSessionId)) {
            throw new IllegalStateException("OAuth session is unavailable");
        }
        OAuthAuthorizationEntity entity = authorizations.findLockedById(authorization.getId())
                .orElseGet(OAuthAuthorizationEntity::new);
        if (entity.invalidatedAt() != null) {
            throw new IllegalStateException("invalidated OAuth authorization cannot be changed");
        }
        byte[] previousRefreshDigest = entity.refreshTokenDigest();
        mapAuthorization(authorization, entity);
        authorizations.saveAndFlush(entity);
        rotateRefreshHistory(entity, previousRefreshDigest);
    }

    @Override
    @Transactional
    public void remove(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        authorizations.findById(authorization.getId())
                .map(OAuthAuthorizationEntity::sessionId)
                .ifPresent(this::lockSession);
        authorizations.findLockedById(authorization.getId())
                .ifPresent(entity -> entity.invalidate(
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
            Optional<OAuthAuthorizationEntity> entity = authorizations.findByStateDigest(digest)
                    .or(() -> authorizations.findByAuthorizationCodeDigest(digest))
                    .or(() -> authorizations.findByAccessTokenDigest(digest));
            if (entity.isPresent()) {
                return usable(entity.get(), new PresentedToken(null, token, digest));
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
            metrics.authorizationCodeLookup();
            return authorizations.findByAuthorizationCodeDigest(digest)
                    .map(this::lockAuthorizationForSession)
                    .map(entity -> usable(entity, new PresentedToken(type, token, digest)))
                    .orElse(null);
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
        UUID sessionId = authorizations.findSessionIdById(authorizationId).orElse(null);
        if (!lockUsableSession(sessionId)) {
            return null;
        }
        OAuthAuthorizationEntity authorization = authorizations.findLockedById(authorizationId)
                .orElse(null);
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
        return usable(
                authorization,
                new PresentedToken(OAuth2ParameterNames.REFRESH_TOKEN, rawToken, digest)
        );
    }

    private OAuth2Authorization usable(OAuthAuthorizationEntity entity, PresentedToken presented) {
        return isUsable(entity) ? toObject(entity, presented) : null;
    }

    private boolean isUsable(OAuthAuthorizationEntity entity) {
        if (entity.invalidatedAt() != null) {
            return false;
        }
        if (entity.sessionId() == null) {
            return true;
        }
        return sessions.findById(entity.sessionId())
                .map(SessionEntity::toDomain)
                .filter(session -> session.status() == SessionStatus.ACTIVE)
                .filter(session -> clock.instant().isBefore(session.expiresAt()))
                .isPresent();
    }

    private OAuthAuthorizationEntity lockAuthorizationForSession(OAuthAuthorizationEntity candidate) {
        if (!lockUsableSession(candidate.sessionId())) {
            return null;
        }
        return authorizations.findLockedById(candidate.getId()).orElse(candidate);
    }

    private boolean lockUsableSession(UUID sessionId) {
        if (sessionId == null) {
            return true;
        }
        SessionEntity session = sessions.findLockedById(sessionId).orElse(null);
        return session != null
                && session.toDomain().status() == SessionStatus.ACTIVE
                && clock.instant().isBefore(session.toDomain().expiresAt());
    }

    private SessionEntity lockSession(UUID sessionId) {
        return sessions.findLockedById(sessionId)
                .orElseThrow(() -> new IllegalStateException("OAuth session is missing"));
    }

    private void compromise(
            OAuthRefreshTokenEntity refresh,
            OAuthAuthorizationEntity authorization,
            Instant now
    ) {
        refresh.detectReuse(now);
        metrics.refreshReplayDetected();
        authorization.invalidate(now, OAuthAuthorizationInvalidationReason.REFRESH_TOKEN_REUSED);
        if (authorization.sessionId() == null) {
            return;
        }
        sessions.findLockedById(authorization.sessionId()).ifPresent(session -> {
            session.apply(session.toDomain().compromise(now));
            audit.append(new SecurityAuditEvent(
                    SecurityAuditType.REFRESH_TOKEN_REUSED,
                    session.accountId(),
                    session.id(),
                    null,
                    "oauth2-token",
                    now
            ));
        });
    }

    private void rotateRefreshHistory(OAuthAuthorizationEntity entity, byte[] previousDigest) {
        byte[] currentDigest = entity.refreshTokenDigest();
        if (currentDigest == null || TokenDigest.matches(previousDigest, currentDigest)) {
            return;
        }
        Instant now = clock.instant();
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

        OAuthRefreshTokenEntity previous = refreshTokens.findLockedByTokenDigest(previousDigest)
                .orElseThrow(() -> new DataRetrievalFailureException("OAuth refresh lineage is missing"));
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
        touchSession(entity.sessionId(), now);
        metrics.refreshRotated();
    }

    private void touchSession(UUID sessionId, Instant now) {
        if (sessionId == null) {
            return;
        }
        sessions.findLockedById(sessionId).ifPresent(session -> {
            session.apply(session.toDomain().touch(now));
            audit.append(new SecurityAuditEvent(
                    SecurityAuditType.REFRESH_TOKEN_ROTATED,
                    session.accountId(),
                    session.id(),
                    null,
                    "oauth2-token",
                    now
            ));
        });
    }

    private void mapAuthorization(OAuth2Authorization authorization, OAuthAuthorizationEntity entity) {
        entity.id(authorization.getId());
        entity.registeredClientId(authorization.getRegisteredClientId());
        entity.principalName(authorization.getPrincipalName());
        UUID sessionId = sessionId(authorization);
        if (sessionId != null) {
            entity.sessionId(sessionId);
        }
        entity.authorizationGrantType(authorization.getAuthorizationGrantType().getValue());
        entity.authorizedScopes(StringUtils.collectionToCommaDelimitedString(authorization.getAuthorizedScopes()));

        String state = authorization.getAttribute(OAuth2ParameterNames.STATE);
        entity.stateDigest(state == null ? null : TokenDigest.digestOrRestore(state));
        entity.attributes(json.writeMap(attributesWithProtectedState(
                authorization.getAttributes(),
                tokenValue(entity.stateDigest(), OAuth2ParameterNames.STATE, null)
        )));
        setToken(authorization.getToken(OAuth2AuthorizationCode.class),
                entity::authorizationCodeDigest,
                entity::authorizationCodeIssuedAt,
                entity::authorizationCodeExpiresAt,
                entity::authorizationCodeMetadata);
        OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getAccessToken();
        setToken(access,
                entity::accessTokenDigest,
                entity::accessTokenIssuedAt,
                entity::accessTokenExpiresAt,
                entity::accessTokenMetadata);
        if (access != null) {
            entity.accessTokenType(access.getToken().getTokenType().getValue());
            entity.accessTokenScopes(StringUtils.collectionToCommaDelimitedString(access.getToken().getScopes()));
        }
        setToken(authorization.getRefreshToken(),
                entity::refreshTokenDigest,
                entity::refreshTokenIssuedAt,
                entity::refreshTokenExpiresAt,
                entity::refreshTokenMetadata);
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
            throw new DataRetrievalFailureException(
                    "Registered OAuth client '" + entity.registeredClientId() + "' was not found"
            );
        }
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(client)
                .id(entity.getId())
                .principalName(entity.principalName())
                .authorizationGrantType(grantType(entity.authorizationGrantType()))
                .authorizedScopes(values(entity.authorizedScopes()))
                .attributes(attributes -> attributes.putAll(attributesWithProtectedState(
                        json.readMap(entity.attributes()),
                        tokenValue(entity.stateDigest(), OAuth2ParameterNames.STATE, presented)
                )));
        if (entity.authorizationCodeDigest() != null) {
            OAuth2AuthorizationCode code = new OAuth2AuthorizationCode(
                    tokenValue(entity.authorizationCodeDigest(), OAuth2ParameterNames.CODE, presented),
                    entity.authorizationCodeIssuedAt(),
                    entity.authorizationCodeExpiresAt()
            );
            builder.token(code, metadata -> metadata.putAll(json.readMap(entity.authorizationCodeMetadata())));
        }
        if (entity.accessTokenDigest() != null) {
            OAuth2AccessToken access = new OAuth2AccessToken(
                    accessTokenType(entity.accessTokenType()),
                    tokenValue(entity.accessTokenDigest(), OAuth2ParameterNames.ACCESS_TOKEN, presented),
                    entity.accessTokenIssuedAt(),
                    entity.accessTokenExpiresAt(),
                    values(entity.accessTokenScopes())
            );
            builder.token(access, metadata -> metadata.putAll(json.readMap(entity.accessTokenMetadata())));
        }
        if (entity.refreshTokenDigest() != null) {
            OAuth2RefreshToken refresh = new OAuth2RefreshToken(
                    tokenValue(entity.refreshTokenDigest(), OAuth2ParameterNames.REFRESH_TOKEN, presented),
                    entity.refreshTokenIssuedAt(),
                    entity.refreshTokenExpiresAt()
            );
            builder.token(refresh, metadata -> metadata.putAll(json.readMap(entity.refreshTokenMetadata())));
        }
        return builder.build();
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
        if (AuthorizationGrantType.CLIENT_CREDENTIALS.getValue().equals(value)) {
            return AuthorizationGrantType.CLIENT_CREDENTIALS;
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
