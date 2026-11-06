package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.IamMaintenanceOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.IamRetentionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Component
@ConditionalOnProperty(
        name = {"iam.session.enabled", "iam.operations.cleanup-enabled"},
        havingValue = "true"
)
public final class IamRetentionJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(IamRetentionJob.class);

    private final IamMaintenanceOperations maintenance;
    private final Clock clock;

    public IamRetentionJob(IamMaintenanceOperations maintenance, Clock clock) {
        this.maintenance = maintenance;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${iam.operations.cleanup-initial-delay:PT15M}",
            fixedDelayString = "${iam.operations.cleanup-interval:PT15M}"
    )
    void cleanup() {
        String scheduleId = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();
        IamRetentionResult result = maintenance.cleanup(new OperatorContext(
                "iam-retention-scheduler",
                "scheduled-iam-retention-" + scheduleId
        ));
        LOGGER.info(
                "Completed bounded IAM retention; expired_sessions={}; "
                        + "invalidated_authorizations={}; deleted_refresh_tokens={}; "
                        + "deleted_authorizations={}; deleted_sessions={}; "
                        + "deleted_audit_events={}; deleted_signing_keys={}",
                result.expiredSessions(),
                result.invalidatedAuthorizations(),
                result.deletedRefreshTokens(),
                result.deletedAuthorizations(),
                result.deletedSessions(),
                result.deletedAuditEvents(),
                result.deletedSigningKeys()
        );
    }
}
