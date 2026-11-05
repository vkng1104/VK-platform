package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
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
public class SessionEntity {
    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    @Convert(converter = SessionStatusConverter.class)
    @Column(nullable = false)
    private SessionStatus status;

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

    @Version
    @Column(nullable = false)
    private long version;

    protected SessionEntity() {
    }

    public SessionEntity(AuthenticationSession session) {
        this.id = session.id();
        this.accountId = session.accountId();
        this.clientId = session.clientId();
        this.status = session.status();
        this.createdAt = session.createdAt();
        this.lastRefreshedAt = session.lastRefreshedAt();
        this.expiresAt = session.expiresAt();
        this.revokedAt = session.revokedAt();
        this.compromisedAt = session.compromisedAt();
        this.revokeReason = session.revokeReason();
    }

    public AuthenticationSession toDomain() {
        return new AuthenticationSession(
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

    public void apply(AuthenticationSession session) {
        if (!id.equals(session.id())
                || !accountId.equals(session.accountId())
                || !clientId.equals(session.clientId())
                || !createdAt.equals(session.createdAt())
                || !expiresAt.equals(session.expiresAt())) {
            throw new IllegalArgumentException("session identity cannot change");
        }
        status = session.status();
        lastRefreshedAt = session.lastRefreshedAt();
        revokedAt = session.revokedAt();
        compromisedAt = session.compromisedAt();
        revokeReason = session.revokeReason();
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }
}
