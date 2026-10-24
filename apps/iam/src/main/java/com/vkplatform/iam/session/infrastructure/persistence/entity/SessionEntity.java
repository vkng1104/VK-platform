package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionStatus;
import com.vkplatform.iam.session.infrastructure.persistence.converter.SessionStatusConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected SessionEntity() {
    }

    public SessionEntity(AuthenticationSession session) {
        this.id = session.id();
        this.accountId = session.accountId();
        this.clientId = session.clientId();
        this.status = session.status();
        this.createdAt = session.createdAt();
        this.expiresAt = session.expiresAt();
    }

    public AuthenticationSession toDomain() {
        return new AuthenticationSession(id, accountId, clientId, status, createdAt, expiresAt);
    }
}
