package com.vkplatform.iam.session.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oauth_authorizations", schema = "iam_session")
public class OAuthAuthorizationEntity implements Persistable<String> {
    @Id
    private String id;

    @Column(name = "registered_client_id", nullable = false, length = 100)
    private String registeredClientId;

    @Column(name = "principal_name", nullable = false, length = 200)
    private String principalName;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "authorization_grant_type", nullable = false, length = 100)
    private String authorizationGrantType;

    @Column(name = "authorized_scopes", length = 1000)
    private String authorizedScopes;

    @Column(nullable = false, columnDefinition = "text")
    private String attributes;

    @Column(name = "state_digest")
    private byte[] stateDigest;

    @Column(name = "authorization_code_digest")
    private byte[] authorizationCodeDigest;

    @Column(name = "authorization_code_issued_at")
    private Instant authorizationCodeIssuedAt;

    @Column(name = "authorization_code_expires_at")
    private Instant authorizationCodeExpiresAt;

    @Column(name = "authorization_code_metadata", columnDefinition = "text")
    private String authorizationCodeMetadata;

    @Column(name = "access_token_digest")
    private byte[] accessTokenDigest;

    @Column(name = "access_token_issued_at")
    private Instant accessTokenIssuedAt;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "access_token_metadata", columnDefinition = "text")
    private String accessTokenMetadata;

    @Column(name = "access_token_type", length = 100)
    private String accessTokenType;

    @Column(name = "access_token_scopes", length = 1000)
    private String accessTokenScopes;

    @Column(name = "refresh_token_digest")
    private byte[] refreshTokenDigest;

    @Column(name = "refresh_token_issued_at")
    private Instant refreshTokenIssuedAt;

    @Column(name = "refresh_token_expires_at")
    private Instant refreshTokenExpiresAt;

    @Column(name = "refresh_token_metadata", columnDefinition = "text")
    private String refreshTokenMetadata;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "invalidation_reason", length = 100)
    private String invalidationReason;

    @Version
    @Column(nullable = false)
    private long version;

    @Transient
    private boolean newEntity = true;

    public OAuthAuthorizationEntity() {
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

    public String registeredClientId() { return registeredClientId; }
    public String principalName() { return principalName; }
    public UUID sessionId() { return sessionId; }
    public String authorizationGrantType() { return authorizationGrantType; }
    public String authorizedScopes() { return authorizedScopes; }
    public String attributes() { return attributes; }
    public byte[] stateDigest() { return copy(stateDigest); }
    public byte[] authorizationCodeDigest() { return copy(authorizationCodeDigest); }
    public Instant authorizationCodeIssuedAt() { return authorizationCodeIssuedAt; }
    public Instant authorizationCodeExpiresAt() { return authorizationCodeExpiresAt; }
    public String authorizationCodeMetadata() { return authorizationCodeMetadata; }
    public byte[] accessTokenDigest() { return copy(accessTokenDigest); }
    public Instant accessTokenIssuedAt() { return accessTokenIssuedAt; }
    public Instant accessTokenExpiresAt() { return accessTokenExpiresAt; }
    public String accessTokenMetadata() { return accessTokenMetadata; }
    public String accessTokenType() { return accessTokenType; }
    public String accessTokenScopes() { return accessTokenScopes; }
    public byte[] refreshTokenDigest() { return copy(refreshTokenDigest); }
    public Instant refreshTokenIssuedAt() { return refreshTokenIssuedAt; }
    public Instant refreshTokenExpiresAt() { return refreshTokenExpiresAt; }
    public String refreshTokenMetadata() { return refreshTokenMetadata; }
    public Instant invalidatedAt() { return invalidatedAt; }
    public String invalidationReason() { return invalidationReason; }

    public void id(String value) { id = value; }
    public void registeredClientId(String value) { registeredClientId = value; }
    public void principalName(String value) { principalName = value; }
    public void sessionId(UUID value) { sessionId = value; }
    public void authorizationGrantType(String value) { authorizationGrantType = value; }
    public void authorizedScopes(String value) { authorizedScopes = value; }
    public void attributes(String value) { attributes = value; }
    public void stateDigest(byte[] value) { stateDigest = copy(value); }
    public void authorizationCodeDigest(byte[] value) { authorizationCodeDigest = copy(value); }
    public void authorizationCodeIssuedAt(Instant value) { authorizationCodeIssuedAt = value; }
    public void authorizationCodeExpiresAt(Instant value) { authorizationCodeExpiresAt = value; }
    public void authorizationCodeMetadata(String value) { authorizationCodeMetadata = value; }
    public void accessTokenDigest(byte[] value) { accessTokenDigest = copy(value); }
    public void accessTokenIssuedAt(Instant value) { accessTokenIssuedAt = value; }
    public void accessTokenExpiresAt(Instant value) { accessTokenExpiresAt = value; }
    public void accessTokenMetadata(String value) { accessTokenMetadata = value; }
    public void accessTokenType(String value) { accessTokenType = value; }
    public void accessTokenScopes(String value) { accessTokenScopes = value; }
    public void refreshTokenDigest(byte[] value) { refreshTokenDigest = copy(value); }
    public void refreshTokenIssuedAt(Instant value) { refreshTokenIssuedAt = value; }
    public void refreshTokenExpiresAt(Instant value) { refreshTokenExpiresAt = value; }
    public void refreshTokenMetadata(String value) { refreshTokenMetadata = value; }

    public void invalidate(Instant now, String reason) {
        if (invalidatedAt == null) {
            invalidatedAt = now;
            invalidationReason = reason;
        }
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }
}
