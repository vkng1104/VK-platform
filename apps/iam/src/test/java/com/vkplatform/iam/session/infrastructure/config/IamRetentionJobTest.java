package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.IamMaintenanceOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.IamRetentionResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamRetentionJobTest {
    @Test
    void delegatesOneBoundedPassWithAStableNonSecretOperationId() {
        IamMaintenanceOperations maintenance = mock(IamMaintenanceOperations.class);
        OperatorContext context = new OperatorContext(
                "iam-retention-scheduler",
                "scheduled-iam-retention-2026-10-30T04:00:00Z"
        );
        when(maintenance.cleanup(context)).thenReturn(new IamRetentionResult(
                1, 2, 3, 4, 5, 6, 7
        ));

        new IamRetentionJob(
                maintenance,
                Clock.fixed(Instant.parse("2026-10-30T04:00:00.987Z"), ZoneOffset.UTC)
        ).cleanup();

        verify(maintenance).cleanup(context);
    }
}
