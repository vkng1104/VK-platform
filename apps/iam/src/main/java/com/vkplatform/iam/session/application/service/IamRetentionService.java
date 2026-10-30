package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.session.api.IamMaintenanceOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.IamRetentionResult;
import com.vkplatform.iam.session.application.port.out.IamRetentionRepository;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import com.vkplatform.iam.session.domain.IamRetentionPolicy;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public class IamRetentionService implements IamMaintenanceOperations {
    private static final String OPERATION = "cleanup-iam-data";

    private final IamRetentionRepository repository;
    private final SecurityAuditOperations audit;
    private final IamRetentionPolicy policy;
    private final IamSecurityTelemetry telemetry;
    private final Clock clock;

    public IamRetentionService(
            IamRetentionRepository repository,
            SecurityAuditOperations audit,
            IamRetentionPolicy policy,
            IamSecurityTelemetry telemetry,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.audit = Objects.requireNonNull(audit);
        this.policy = Objects.requireNonNull(policy);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public IamRetentionResult cleanup(OperatorContext context) {
        Objects.requireNonNull(context);
        long startedAt = telemetry.start();
        repository.lockOperation(context.operationId());
        IamRetentionRepository.StoredRetentionOperation previous = repository.findOperation(
                context.operationId()
        ).orElse(null);
        if (previous != null) {
            if (!OPERATION.equals(previous.operationType())
                    || !context.actorId().equals(previous.actorId())) {
                throw new IllegalArgumentException("operator operation ID is already in use");
            }
            if (previous.result() == null) {
                throw new IllegalStateException("stored retention result is missing");
            }
            return previous.result();
        }

        Instant now = clock.instant();
        IamRetentionRepository.RetentionCutoffs cutoffs = new IamRetentionRepository.RetentionCutoffs(
                now.minus(policy.sessionRetention()),
                now.minus(policy.authorizationRetention()),
                now.minus(policy.auditRetention()),
                now.minus(policy.signingKeyRetention())
        );
        IamRetentionResult result = repository.cleanup(
                cutoffs,
                now,
                policy.batchSize()
        );
        int deletedAuditEvents = audit.deleteRetainedBefore(
                cutoffs.auditCutoff(),
                policy.batchSize()
        );
        result = result.withDeletedAuditEvents(
                result.deletedAuditEvents() + deletedAuditEvents
        );
        repository.complete(context, result, now);
        telemetry.retentionAfterCommit(result, startedAt);
        return result;
    }
}
