package com.vkplatform.iam.session.infrastructure.observability;

import com.vkplatform.iam.session.api.result.IamRetentionResult;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class IamSecurityMetricsTest {
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void recordsOnlyBoundedOperationAndOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IamSecurityMetrics metrics = new IamSecurityMetrics(registry);

        metrics.record(
                IamSecurityTelemetry.Operation.SESSION_REVOCATION,
                IamSecurityTelemetry.Outcome.SUCCESS,
                metrics.start()
        );

        assertThat(registry.get("iam.security.operations")
                .tags("operation", "session_revocation", "outcome", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("iam.security.operation.duration")
                .tags("operation", "session_revocation", "outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .doesNotContain("account_id", "session_id", "request_id", "operation_id")
        );
    }

    @Test
    void publishesCommittedOutcomesAndRetentionCountsOnlyAfterCommit() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IamSecurityMetrics metrics = new IamSecurityMetrics(registry);
        TransactionSynchronizationManager.initSynchronization();
        IamRetentionResult result = new IamRetentionResult(2, 0, 3, 0, 0, 1, 0);

        metrics.retentionAfterCommit(result, metrics.start());

        assertThat(registry.find("iam.security.operations").counter()).isNull();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit());
        assertThat(registry.get("iam.security.operations")
                .tags("operation", "retention", "outcome", "success")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("iam.retention.records")
                .tag("category", "expired_sessions").counter().count()).isEqualTo(2);
        assertThat(registry.get("iam.retention.records")
                .tag("category", "deleted_refresh_tokens").counter().count()).isEqualTo(3);
    }

    @Test
    void metricBackendFailuresNeverEscapeIntoSecurityBehavior() {
        IamSecurityMetrics metrics = new IamSecurityMetrics(mock(MeterRegistry.class));

        assertThatCode(() -> metrics.record(
                IamSecurityTelemetry.Operation.TOKEN_ISSUE,
                IamSecurityTelemetry.Outcome.SUCCESS,
                metrics.start()
        )).doesNotThrowAnyException();
    }
}
