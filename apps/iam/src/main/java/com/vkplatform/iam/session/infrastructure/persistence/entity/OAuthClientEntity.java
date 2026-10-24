package com.vkplatform.iam.session.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "oauth_clients", schema = "iam_session")
public class OAuthClientEntity implements Persistable<String> {
    @Id
    private String id;

    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    @Column(name = "client_id_issued_at", nullable = false)
    private Instant clientIdIssuedAt;

    @Column(name = "client_secret", length = 200)
    private String clientSecret;

    @Column(name = "client_secret_expires_at")
    private Instant clientSecretExpiresAt;

    @Column(name = "client_name", nullable = false, length = 200)
    private String clientName;

    @Column(name = "client_authentication_methods", nullable = false, length = 1000)
    private String clientAuthenticationMethods;

    @Column(name = "authorization_grant_types", nullable = false, length = 1000)
    private String authorizationGrantTypes;

    @Column(name = "redirect_uris", length = 2000)
    private String redirectUris;

    @Column(name = "post_logout_redirect_uris", length = 2000)
    private String postLogoutRedirectUris;

    @Column(nullable = false, length = 1000)
    private String scopes;

    @Column(name = "client_settings", nullable = false, columnDefinition = "text")
    private String clientSettings;

    @Column(name = "token_settings", nullable = false, columnDefinition = "text")
    private String tokenSettings;

    @Transient
    private boolean newEntity = true;

    public OAuthClientEntity() {
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newEntity = false;
    }

    public String clientId() {
        return clientId;
    }

    public Instant clientIdIssuedAt() {
        return clientIdIssuedAt;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public Instant clientSecretExpiresAt() {
        return clientSecretExpiresAt;
    }

    public String clientName() {
        return clientName;
    }

    public String clientAuthenticationMethods() {
        return clientAuthenticationMethods;
    }

    public String authorizationGrantTypes() {
        return authorizationGrantTypes;
    }

    public String redirectUris() {
        return redirectUris;
    }

    public String postLogoutRedirectUris() {
        return postLogoutRedirectUris;
    }

    public String scopes() {
        return scopes;
    }

    public String clientSettings() {
        return clientSettings;
    }

    public String tokenSettings() {
        return tokenSettings;
    }

    public void id(String value) {
        id = value;
    }

    public void clientId(String value) {
        clientId = value;
    }

    public void clientIdIssuedAt(Instant value) {
        clientIdIssuedAt = value;
    }

    public void clientSecret(String value) {
        clientSecret = value;
    }

    public void clientSecretExpiresAt(Instant value) {
        clientSecretExpiresAt = value;
    }

    public void clientName(String value) {
        clientName = value;
    }

    public void clientAuthenticationMethods(String value) {
        clientAuthenticationMethods = value;
    }

    public void authorizationGrantTypes(String value) {
        authorizationGrantTypes = value;
    }

    public void redirectUris(String value) {
        redirectUris = value;
    }

    public void postLogoutRedirectUris(String value) {
        postLogoutRedirectUris = value;
    }

    public void scopes(String value) {
        scopes = value;
    }

    public void clientSettings(String value) {
        clientSettings = value;
    }

    public void tokenSettings(String value) {
        tokenSettings = value;
    }
}
