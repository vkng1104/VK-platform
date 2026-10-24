package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.infrastructure.crypto.TokenDigest;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnProperty(
        name = {"iam.oauth.enabled", "iam.session.enabled"},
        havingValue = "true"
)
public class JpaOAuth2AuthorizationService implements OAuth2AuthorizationService {
    private static final String SESSION_ID_DETAIL = "session_id";

    private final SpringDataOAuthAuthorizationRepository authorizations;
    private final AuthenticationSessionRepository sessions;
    private final RegisteredClientRepository clients;
    private final Clock clock;
    private final OAuthJsonCodec json = new OAuthJsonCodec();

    JpaOAuth2AuthorizationService(
            SpringDataOAuthAuthorizationRepository authorizations,
            AuthenticationSessionRepository sessions,
            RegisteredClientRepository clients,
            Clock clock
    ) {
        this.authorizations = authorizations;
        this.sessions = sessions;
        this.clients = clients;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void save(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        OAuthAuthorizationEntity entity = authorizations.findLockedById(authorization.getId())
                .orElseGet(OAuthAuthorizationEntity::new);
        UUID sessionId = sessionId(authorization);
        if (sessionId == null) {
            sessionId = entity.sessionId();
        }
        AuthenticationSession session = requireUsableSession(
                sessionId,
                authorization.getPrincipalName(),
                authorization.getRegisteredClientId()
        );
        mapAuthorization(authorization, session.id(), entity);
        authorizations.saveAndFlush(entity);
    }

    @Override
    @Transactional
    public void remove(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        authorizations.deleteById(authorization.getId());
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
        String type = tokenType == null ? null : tokenType.getValue();
        OAuthAuthorizationEntity entity;
        if (type == null) {
            entity = authorizations.findByStateDigest(digest)
                    .orElseGet(() -> authorizations.findLockedByAuthorizationCodeDigest(digest).orElse(null));
        } else if (OAuth2ParameterNames.STATE.equals(type)) {
            entity = authorizations.findByStateDigest(digest).orElse(null);
        } else if (OAuth2ParameterNames.CODE.equals(type)) {
            entity = authorizations.findLockedByAuthorizationCodeDigest(digest).orElse(null);
        } else {
            return null;
        }
        if (entity == null || !isUsable(entity)) {
            return null;
        }
        return toObject(entity, new PresentedToken(type, token, digest));
    }

    private AuthenticationSession requireUsableSession(
            UUID sessionId,
            String principalName,
            String registeredClientId
    ) {
        if (sessionId == null) {
            throw new IllegalStateException("OAuth browser session is missing");
        }
        UUID accountId = accountId(principalName);
        return sessions.findById(sessionId)
                .filter(session -> session.canAuthorize(accountId, clientId(registeredClientId), clock.instant()))
                .orElseThrow(() -> new IllegalStateException("OAuth browser session is unavailable"));
    }

    private boolean isUsable(OAuthAuthorizationEntity entity) {
        try {
            requireUsableSession(entity.sessionId(), entity.principalName(), entity.registeredClientId());
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private String clientId(String registeredClientId) {
        RegisteredClient client = clients.findById(registeredClientId);
        if (client == null) {
            throw new DataRetrievalFailureException("OAuth registered client is missing");
        }
        return client.getClientId();
    }

    private void mapAuthorization(
            OAuth2Authorization authorization,
            UUID sessionId,
            OAuthAuthorizationEntity entity
    ) {
        if (!AuthorizationGrantType.AUTHORIZATION_CODE.equals(authorization.getAuthorizationGrantType())) {
            throw new IllegalArgumentException("only the authorization_code grant is supported");
        }
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

        OAuth2Authorization.Token<OAuth2AuthorizationCode> code =
                authorization.getToken(OAuth2AuthorizationCode.class);
        if (code == null) {
            entity.authorizationCodeDigest(null);
            entity.authorizationCodeIssuedAt(null);
            entity.authorizationCodeExpiresAt(null);
            entity.authorizationCodeMetadataJson(null);
            return;
        }
        OAuth2Token value = code.getToken();
        entity.authorizationCodeDigest(TokenDigest.digestOrRestore(value.getTokenValue()));
        entity.authorizationCodeIssuedAt(value.getIssuedAt());
        entity.authorizationCodeExpiresAt(value.getExpiresAt());
        entity.authorizationCodeMetadataJson(json.writeMap(code.getMetadata()));
    }

    private OAuth2Authorization toObject(OAuthAuthorizationEntity entity, PresentedToken presented) {
        RegisteredClient client = clients.findById(entity.registeredClientId());
        if (client == null) {
            throw new DataRetrievalFailureException("OAuth registered client is missing");
        }
        OAuth2Authorization.Builder builder = OAuth2Authorization.withRegisteredClient(client)
                .id(entity.getId())
                .principalName(entity.principalName())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
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

    private record PresentedToken(String type, String value, byte[] digest) {
    }
}
