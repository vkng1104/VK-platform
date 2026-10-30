package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.IamRetentionResult;
import com.vkplatform.iam.session.application.port.out.IamRetentionRepository;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import com.vkplatform.iam.session.domain.IamRetentionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamRetentionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-30T04:00:00Z");
    private IamRetentionRepository repository;
    private SecurityAuditOperations audit;
    private IamSecurityTelemetry telemetry;
    private IamRetentionService service;

    @BeforeEach
    void setUp() {
        repository = mock(IamRetentionRepository.class);
        audit = mock(SecurityAuditOperations.class);
        telemetry = mock(IamSecurityTelemetry.class);
        service = new IamRetentionService(
                repository,
                audit,
                new IamRetentionPolicy(
                        Duration.ofDays(90),
                        Duration.ofDays(30),
                        Duration.ofDays(365),
                        Duration.ofDays(90),
                        100
                ),
                telemetry,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void computesIndependentCutoffsAggregatesAuditAndStoresTheResult() {
        OperatorContext context = new OperatorContext("retention-operator", "cleanup-1");
        IamRetentionResult persistence = new IamRetentionResult(2, 3, 4, 5, 6, 7, 8);
        when(repository.findOperation("cleanup-1")).thenReturn(Optional.empty());
        when(repository.cleanup(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.eq(100))).thenReturn(persistence);
        when(audit.deleteRetainedBefore(NOW.minus(Duration.ofDays(365)), 100)).thenReturn(9);

        IamRetentionResult result = service.cleanup(context);

        ArgumentCaptor<IamRetentionRepository.RetentionCutoffs> cutoffs = ArgumentCaptor.forClass(
                IamRetentionRepository.RetentionCutoffs.class
        );
        verify(repository).cleanup(cutoffs.capture(), org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.eq(100));
        assertThat(cutoffs.getValue()).isEqualTo(new IamRetentionRepository.RetentionCutoffs(
                NOW.minus(Duration.ofDays(90)),
                NOW.minus(Duration.ofDays(30)),
                NOW.minus(Duration.ofDays(365)),
                NOW.minus(Duration.ofDays(90))
        ));
        assertThat(result.deletedAuditEvents()).isEqualTo(16);
        verify(repository).complete(context, result, NOW);
        verify(telemetry).retentionAfterCommit(result, 0L);
    }

    @Test
    void replaysAStoredResultWithoutPerformingCleanup() {
        OperatorContext context = new OperatorContext("retention-operator", "cleanup-retry");
        IamRetentionResult stored = new IamRetentionResult(1, 2, 3, 4, 5, 6, 7);
        when(repository.findOperation("cleanup-retry")).thenReturn(Optional.of(
                new IamRetentionRepository.StoredRetentionOperation(
                        "cleanup-iam-data",
                        "retention-operator",
                        stored
                )
        ));

        assertThat(service.cleanup(context)).isEqualTo(stored);

        verify(repository).lockOperation("cleanup-retry");
        verify(repository, never()).cleanup(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(audit, never()).deleteRetainedBefore(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void rejectsAnOperationIdConflictBeforeMutation() {
        when(repository.findOperation("conflict")).thenReturn(Optional.of(
                new IamRetentionRepository.StoredRetentionOperation(
                        "rotate-signing-key",
                        "retention-operator",
                        null
                )
        ));

        assertThatThrownBy(() -> service.cleanup(new OperatorContext(
                "retention-operator",
                "conflict"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already in use");
        verify(repository, never()).complete(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
