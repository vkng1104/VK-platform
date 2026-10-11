package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientEntity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
public class JpaRegisteredClientRepository implements RegisteredClientRepository {
    private final SpringDataOAuthClientRepository clients;
    private final OAuthJsonCodec json = new OAuthJsonCodec();

    JpaRegisteredClientRepository(SpringDataOAuthClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public void save(RegisteredClient client) {
        Assert.notNull(client, "registeredClient cannot be null");
        clients.save(toEntity(client));
    }

    @Override
    public RegisteredClient findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        return clients.findById(id).map(this::toObject).orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        Assert.hasText(clientId, "clientId cannot be empty");
        return clients.findByClientId(clientId).map(this::toObject).orElse(null);
    }

    RegisteredClient findLockedByClientId(String clientId) {
        Assert.hasText(clientId, "clientId cannot be empty");
        return clients.findLockedByClientId(clientId).map(this::toObject).orElse(null);
    }

    private RegisteredClient toObject(OAuthClientEntity entity) {
        RegisteredClient.Builder builder = RegisteredClient.withId(entity.getId())
                .clientId(entity.clientId())
                .clientIdIssuedAt(entity.clientIdIssuedAt())
                .clientSecret(entity.clientSecret())
                .clientSecretExpiresAt(entity.clientSecretExpiresAt())
                .clientName(entity.clientName())
                .clientAuthenticationMethods(methods -> values(entity.clientAuthenticationMethods())
                        .forEach(value -> methods.add(authenticationMethod(value))))
                .authorizationGrantTypes(types -> values(entity.authorizationGrantTypes())
                        .forEach(value -> types.add(grantType(value))))
                .redirectUris(uris -> uris.addAll(values(entity.redirectUris())))
                .postLogoutRedirectUris(uris -> uris.addAll(values(entity.postLogoutRedirectUris())))
                .scopes(scopes -> scopes.addAll(values(entity.scopes())));
        builder.clientSettings(ClientSettings.withSettings(json.readMap(entity.clientSettings())).build());
        builder.tokenSettings(TokenSettings.withSettings(json.readMap(entity.tokenSettings())).build());
        return builder.build();
    }

    private OAuthClientEntity toEntity(RegisteredClient client) {
        OAuthClientEntity entity = clients.findById(client.getId()).orElseGet(OAuthClientEntity::new);
        entity.id(client.getId());
        entity.clientId(client.getClientId());
        entity.clientIdIssuedAt(client.getClientIdIssuedAt());
        entity.clientSecret(client.getClientSecret());
        entity.clientSecretExpiresAt(client.getClientSecretExpiresAt());
        entity.clientName(client.getClientName());
        entity.clientAuthenticationMethods(values(client.getClientAuthenticationMethods()));
        entity.authorizationGrantTypes(values(client.getAuthorizationGrantTypes()));
        entity.redirectUris(StringUtils.collectionToCommaDelimitedString(client.getRedirectUris()));
        entity.postLogoutRedirectUris(StringUtils.collectionToCommaDelimitedString(client.getPostLogoutRedirectUris()));
        entity.scopes(StringUtils.collectionToCommaDelimitedString(client.getScopes()));
        entity.clientSettings(json.writeMap(client.getClientSettings().getSettings()));
        entity.tokenSettings(json.writeMap(client.getTokenSettings().getSettings()));
        return entity;
    }

    private static Set<String> values(String value) {
        return value == null ? Set.of() : StringUtils.commaDelimitedListToSet(value);
    }

    private static String values(Set<?> values) {
        List<String> result = new ArrayList<>(values.size());
        values.forEach(value -> result.add(value instanceof ClientAuthenticationMethod method
                ? method.getValue()
                : ((AuthorizationGrantType) value).getValue()));
        return StringUtils.collectionToCommaDelimitedString(result);
    }

    private static ClientAuthenticationMethod authenticationMethod(String value) {
        if (ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue().equals(value)) {
            return ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
        }
        if (ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue().equals(value)) {
            return ClientAuthenticationMethod.CLIENT_SECRET_POST;
        }
        if (ClientAuthenticationMethod.NONE.getValue().equals(value)) {
            return ClientAuthenticationMethod.NONE;
        }
        return new ClientAuthenticationMethod(value);
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
}
