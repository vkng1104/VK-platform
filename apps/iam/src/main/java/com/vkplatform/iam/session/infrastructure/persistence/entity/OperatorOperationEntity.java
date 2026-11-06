package com.vkplatform.iam.session.infrastructure.persistence.entity;

import com.vkplatform.iam.platform.persistence.AbstractAssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "operator_operations", schema = "iam_session")
public class OperatorOperationEntity extends AbstractAssignedIdEntity<String> {
    @Id
    @Column(name = "operation_id", length = 128)
    private String operationId;

    @Column(name = "operation_type", nullable = false, length = 64)
    private String operationType;

    @Column(name = "actor_id", nullable = false, length = 128)
    private String actorId;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(name = "result_key_id", length = 128)
    private String resultKeyId;

    @Column(name = "result_count")
    private Integer resultCount;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    protected OperatorOperationEntity() {
    }

    public OperatorOperationEntity(
            String operationId,
            String operationType,
            String actorId,
            String targetId,
            String resultKeyId,
            Integer resultCount,
            Instant completedAt
    ) {
        this.operationId = operationId;
        this.operationType = operationType;
        this.actorId = actorId;
        this.targetId = targetId;
        this.resultKeyId = resultKeyId;
        this.resultCount = resultCount;
        this.completedAt = completedAt;
    }

    public String operationType() {
        return operationType;
    }

    @Override
    public String getId() {
        return operationId;
    }

    public String actorId() {
        return actorId;
    }

    public String targetId() {
        return targetId;
    }

    public String resultKeyId() {
        return resultKeyId;
    }

    public Integer resultCount() {
        return resultCount;
    }
}
