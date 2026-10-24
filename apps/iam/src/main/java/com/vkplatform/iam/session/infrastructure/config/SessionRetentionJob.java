package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.application.port.out.SessionRetentionRepository;
import com.vkplatform.iam.session.application.service.SessionRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"iam.session.enabled", "iam.session.cleanup-enabled"},
        havingValue = "true"
)
public final class SessionRetentionJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRetentionJob.class);
    private final SessionRetentionService cleanup;

    public SessionRetentionJob(SessionRetentionService cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(
            initialDelayString = "${iam.session.cleanup-initial-delay:PT15M}",
            fixedDelayString = "${iam.session.cleanup-interval:PT15M}"
    )
    void cleanup() {
        SessionRetentionRepository.CleanupResult result = cleanup.cleanupOnce();
        if (result.totalChanges() > 0) {
            LOGGER.info("Completed bounded IAM session retention cleanup; result={}", result);
        }
    }
}
