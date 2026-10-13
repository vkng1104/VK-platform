package com.vkplatform.iam.session.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "signing_key_events", schema = "iam_session")
public class SigningKeyEventEntity extends AbstractSessionUuidEntity {
    @Id
    private UUID id;

    @Column(name = "key_id", nullable = false, length = 128)
    private String keyId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected SigningKeyEventEntity() {
    }

    public SigningKeyEventEntity(UUID id, String keyId, String eventType, Instant occurredAt) {
        this.id = id;
        this.keyId = keyId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
