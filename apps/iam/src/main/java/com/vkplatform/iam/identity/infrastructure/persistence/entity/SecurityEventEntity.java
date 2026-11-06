package com.vkplatform.iam.identity.infrastructure.persistence.entity;

import com.vkplatform.iam.identity.domain.SecurityEvent;
import com.vkplatform.iam.identity.domain.SecurityEventType;
import com.vkplatform.iam.identity.infrastructure.persistence.converter.SecurityEventTypeConverter;
import com.vkplatform.iam.platform.persistence.AbstractAssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "security_events", schema = "iam_identity")
public class SecurityEventEntity extends AbstractAssignedIdEntity<UUID> {
    @Id
    private UUID id;

    @Convert(converter = SecurityEventTypeConverter.class)
    @Column(name = "event_type", nullable = false)
    private SecurityEventType eventType;

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

    public SecurityEventEntity(SecurityEvent event) {
        this.id = event.id();
        this.eventType = event.type();
        this.accountId = event.accountId();
        this.targetId = event.targetId();
        this.actorId = event.actorId();
        this.requestId = event.requestId();
        this.occurredAt = event.occurredAt();
    }

    @Override
    public UUID getId() {
        return id;
    }
}
