package com.vkplatform.iam.session.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "oauth_client_events", schema = "iam_session")
public class OAuthClientEventEntity extends AbstractSessionUuidEntity {
    @Id
    private UUID id;

    @Column(name = "registered_client_id", nullable = false, length = 100)
    private String registeredClientId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OAuthClientEventEntity() {
    }

    public OAuthClientEventEntity(
            UUID id,
            String registeredClientId,
            String eventType,
            Instant occurredAt
    ) {
        this.id = id;
        this.registeredClientId = registeredClientId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
