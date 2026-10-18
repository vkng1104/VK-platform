package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
import com.vkplatform.iam.session.domain.UserSession;
import com.vkplatform.iam.session.infrastructure.persistence.converter.SessionRevokeReasonConverter;
import com.vkplatform.iam.session.infrastructure.persistence.converter.SessionStatusConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions", schema = "iam_session")
public class SessionEntity extends AbstractSessionUuidEntity {
    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    @Convert(converter = SessionStatusConverter.class)
    @Column(nullable = false)
    private SessionStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_refreshed_at", nullable = false)
    private Instant lastRefreshedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "compromised_at")
    private Instant compromisedAt;

    @Convert(converter = SessionRevokeReasonConverter.class)
    @Column(name = "revoke_reason")
    private SessionRevokeReason revokeReason;

    protected SessionEntity() {
    }

    public SessionEntity(UserSession session) {
        this.id = session.id();
        this.accountId = session.accountId();
        this.clientId = session.clientId();
        this.createdAt = session.createdAt();
        this.expiresAt = session.expiresAt();
        applyMutableState(session);
    }

    public UserSession toDomain() {
        return new UserSession(
                id,
                accountId,
                clientId,
                status,
                createdAt,
                lastRefreshedAt,
                expiresAt,
                revokedAt,
                compromisedAt,
                revokeReason
        );
    }

    public void apply(UserSession session) {
        if (!id.equals(session.id())
                || !accountId.equals(session.accountId())
                || !clientId.equals(session.clientId())
                || !createdAt.equals(session.createdAt())
                || !expiresAt.equals(session.expiresAt())) {
            throw new IllegalArgumentException("immutable session state cannot change");
        }
        applyMutableState(session);
    }

    private void applyMutableState(UserSession session) {
        this.status = session.status();
        this.lastRefreshedAt = session.lastRefreshedAt();
        this.revokedAt = session.revokedAt();
        this.compromisedAt = session.compromisedAt();
        this.revokeReason = session.revokeReason();
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    public SessionStatus status() {
        return status;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
