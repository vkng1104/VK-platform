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

    @Column(name = "registered_client_id", nullable = false, length = 128)
    private String registeredClientId;

    @Column(name = "principal_name", nullable = false, length = 200)
    private String principalName;

    @Column(name = "session_id", nullable = false)
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

    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }
}
