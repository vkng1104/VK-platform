package com.vkplatform.iam.identity.infrastructure.persistence.entity;

import com.vkplatform.iam.identity.api.SecurityAuditEventType;
import com.vkplatform.iam.identity.infrastructure.persistence.converter.SecurityEventTypeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "security_events", schema = "iam_identity")
public class SecurityEventEntity extends AbstractUuidEntity {
    @Id
    private UUID id;

    @Convert(converter = SecurityEventTypeConverter.class)
    @Column(name = "event_type", nullable = false)
    private SecurityAuditEventType eventType;

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

    protected SecurityEventEntity() {
    }

    public SecurityEventEntity(
            UUID id,
            SecurityAuditEventType eventType,
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
