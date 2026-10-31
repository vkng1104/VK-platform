package com.vkplatform.iam.session.infrastructure.observability;

import com.vkplatform.iam.session.api.result.IamRetentionResult;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

@Component
public final class IamSecurityMetrics implements IamSecurityTelemetry {
    private static final Logger LOGGER = LoggerFactory.getLogger(IamSecurityMetrics.class);
    private final MeterRegistry registry;

    public IamSecurityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public long start() {
        return System.nanoTime();
    }

    @Override
    public void record(Operation operation, Outcome outcome, long startedAt) {
        safe(() -> {
            registry.counter(
                    "iam.security.operations",
                    "operation", operation.tag(),
                    "outcome", outcome.tag()
            ).increment();
            registry.timer(
                    "iam.security.operation.duration",
                    "operation", operation.tag(),
                    "outcome", outcome.tag()
            ).record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt)));
        });
    }

    @Override
    public void recordAfterCommit(Operation operation, Outcome outcome, long startedAt) {
        afterCommit(() -> record(operation, outcome, startedAt));
    }

    @Override
    public void retentionAfterCommit(IamRetentionResult result, long startedAt) {
        afterCommit(() -> safe(() -> {
            record(Operation.RETENTION, Outcome.SUCCESS, startedAt);
            retentionCount("expired_sessions", result.expiredSessions());
            retentionCount("invalidated_authorizations", result.invalidatedAuthorizations());
            retentionCount("deleted_refresh_tokens", result.deletedRefreshTokens());
            retentionCount("deleted_authorizations", result.deletedAuthorizations());
            retentionCount("deleted_sessions", result.deletedSessions());
            retentionCount("deleted_audit_events", result.deletedAuditEvents());
            retentionCount("deleted_signing_keys", result.deletedSigningKeys());
        }));
    }

    @Override
    public void recordOperator(Operation operation, Outcome outcome, long startedAt) {
        safe(() -> {
            registry.counter(
                    "iam.operator.executions",
                    "operation", operation.tag(),
                    "outcome", outcome.tag()
            ).increment();
            registry.timer(
                    "iam.operator.duration",
                    "operation", operation.tag(),
                    "outcome", outcome.tag()
            ).record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt)));
        });
    }

    private void retentionCount(String category, int count) {
        if (count > 0) {
            registry.counter("iam.retention.records", "category", category).increment(count);
        }
    }

    private void afterCommit(Runnable action) {
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                action.run();
                            }
                        }
                );
            } else {
                action.run();
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("IAM security metric could not be scheduled");
        }
    }

    private void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            LOGGER.warn("IAM security metric could not be recorded");
        }
    }
}
