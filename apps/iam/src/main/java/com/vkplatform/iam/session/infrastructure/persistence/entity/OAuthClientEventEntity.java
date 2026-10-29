package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.session.domain.OAuthClientEventType;
import com.vkplatform.iam.session.infrastructure.persistence.converter.OAuthClientEventTypeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
    @Convert(converter = OAuthClientEventTypeConverter.class)
    private OAuthClientEventType eventType;

    @Column(name = "actor_id", nullable = false, length = 128)
    private String actorId;

    @Column(name = "operation_id", nullable = false, length = 128)
    private String operationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected OAuthClientEventEntity() {
    }

    public OAuthClientEventEntity(
            UUID id,
            String registeredClientId,
            OAuthClientEventType eventType,
            String actorId,
            String operationId,
            Instant occurredAt
    ) {
        this.id = id;
        this.registeredClientId = registeredClientId;
        this.eventType = eventType;
        this.actorId = actorId;
        this.operationId = operationId;
        this.occurredAt = occurredAt;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
