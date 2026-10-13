package com.vkplatform.iam.session.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "security_events", schema = "iam_identity")
public class SessionSecurityEventEntity extends AbstractSessionUuidEntity {
    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "actor_id", length = 128)
    private String actorId;

    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SessionSecurityEventEntity() {
    }

    public SessionSecurityEventEntity(
            UUID id,
            String eventType,
            UUID accountId,
            UUID targetId,
            String actorId,
            String requestId,
            Instant occurredAt
    ) {
        this.id = id;
        this.eventType = eventType;
        this.accountId = accountId;
        this.targetId = targetId;
        this.actorId = actorId;
        this.requestId = requestId;
        this.occurredAt = occurredAt;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
