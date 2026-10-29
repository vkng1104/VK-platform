package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.infrastructure.persistence.converter.OAuthAuthorizationInvalidationReasonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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

    @Column(name = "registered_client_id", nullable = false, length = 128)
    private String registeredClientId;

    @Column(name = "principal_name", nullable = false, length = 200)
    private String principalName;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "authorization_grant_type", nullable = false, length = 64)
    private String authorizationGrantType;

    @Column(name = "authorized_scopes", nullable = false, length = 1000)
    private String authorizedScopes;

    @Column(name = "attributes_json", nullable = false, columnDefinition = "text")
    private String attributesJson;

    @Column(name = "state_digest")
    private byte[] stateDigest;

    @Column(name = "authorization_code_digest")
    private byte[] authorizationCodeDigest;

    @Column(name = "authorization_code_issued_at")
    private Instant authorizationCodeIssuedAt;

    @Column(name = "authorization_code_expires_at")
    private Instant authorizationCodeExpiresAt;

    @Column(name = "authorization_code_metadata_json", columnDefinition = "text")
    private String authorizationCodeMetadataJson;

    @Column(name = "access_token_digest")
    private byte[] accessTokenDigest;

    @Column(name = "access_token_issued_at")
    private Instant accessTokenIssuedAt;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "access_token_metadata_json", columnDefinition = "text")
    private String accessTokenMetadataJson;

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

    @Column(name = "refresh_token_metadata_json", columnDefinition = "text")
    private String refreshTokenMetadataJson;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Convert(converter = OAuthAuthorizationInvalidationReasonConverter.class)
    @Column(name = "invalidation_reason", length = 100)
    private OAuthAuthorizationInvalidationReason invalidationReason;

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
    public String attributesJson() { return attributesJson; }
    public byte[] stateDigest() { return copy(stateDigest); }
    public byte[] authorizationCodeDigest() { return copy(authorizationCodeDigest); }
    public Instant authorizationCodeIssuedAt() { return authorizationCodeIssuedAt; }
    public Instant authorizationCodeExpiresAt() { return authorizationCodeExpiresAt; }
    public String authorizationCodeMetadataJson() { return authorizationCodeMetadataJson; }
    public byte[] accessTokenDigest() { return copy(accessTokenDigest); }
    public Instant accessTokenIssuedAt() { return accessTokenIssuedAt; }
    public Instant accessTokenExpiresAt() { return accessTokenExpiresAt; }
    public String accessTokenMetadataJson() { return accessTokenMetadataJson; }
    public String accessTokenType() { return accessTokenType; }
    public String accessTokenScopes() { return accessTokenScopes; }
    public byte[] refreshTokenDigest() { return copy(refreshTokenDigest); }
    public Instant refreshTokenIssuedAt() { return refreshTokenIssuedAt; }
    public Instant refreshTokenExpiresAt() { return refreshTokenExpiresAt; }
    public String refreshTokenMetadataJson() { return refreshTokenMetadataJson; }
    public Instant invalidatedAt() { return invalidatedAt; }
    public OAuthAuthorizationInvalidationReason invalidationReason() { return invalidationReason; }

    public void id(String value) { id = value; }
    public void registeredClientId(String value) { registeredClientId = value; }
    public void principalName(String value) { principalName = value; }
    public void sessionId(UUID value) { sessionId = value; }
    public void authorizationGrantType(String value) { authorizationGrantType = value; }
    public void authorizedScopes(String value) { authorizedScopes = value; }
    public void attributesJson(String value) { attributesJson = value; }
    public void stateDigest(byte[] value) { stateDigest = copy(value); }
    public void authorizationCodeDigest(byte[] value) { authorizationCodeDigest = copy(value); }
    public void authorizationCodeIssuedAt(Instant value) { authorizationCodeIssuedAt = value; }
    public void authorizationCodeExpiresAt(Instant value) { authorizationCodeExpiresAt = value; }
    public void authorizationCodeMetadataJson(String value) { authorizationCodeMetadataJson = value; }
    public void accessTokenDigest(byte[] value) { accessTokenDigest = copy(value); }
    public void accessTokenIssuedAt(Instant value) { accessTokenIssuedAt = value; }
    public void accessTokenExpiresAt(Instant value) { accessTokenExpiresAt = value; }
    public void accessTokenMetadataJson(String value) { accessTokenMetadataJson = value; }
    public void accessTokenType(String value) { accessTokenType = value; }
    public void accessTokenScopes(String value) { accessTokenScopes = value; }
    public void refreshTokenDigest(byte[] value) { refreshTokenDigest = copy(value); }
    public void refreshTokenIssuedAt(Instant value) { refreshTokenIssuedAt = value; }
    public void refreshTokenExpiresAt(Instant value) { refreshTokenExpiresAt = value; }
    public void refreshTokenMetadataJson(String value) { refreshTokenMetadataJson = value; }

    public void invalidate(Instant now, OAuthAuthorizationInvalidationReason reason) {
        if (invalidatedAt == null) {
            invalidatedAt = now;
            invalidationReason = reason;
        }
    }

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }
}
