package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.IamRetentionResult;

import java.time.Instant;
import java.util.Optional;

public interface IamRetentionRepository {
    void lockOperation(String operationId);

    Optional<StoredRetentionOperation> findOperation(String operationId);

    IamRetentionResult cleanup(
            RetentionCutoffs cutoffs,
            Instant now,
            int batchSize
    );

    void complete(
            OperatorContext context,
            IamRetentionResult result,
            Instant completedAt
    );

    record RetentionCutoffs(
            Instant sessionCutoff,
            Instant authorizationCutoff,
            Instant auditCutoff,
            Instant signingKeyCutoff
    ) {
    }

    record StoredRetentionOperation(
            String operationType,
            String actorId,
            IamRetentionResult result
    ) {
    }
}
