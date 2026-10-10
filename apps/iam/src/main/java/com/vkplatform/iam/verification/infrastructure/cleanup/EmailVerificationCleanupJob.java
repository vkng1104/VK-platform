package com.vkplatform.iam.verification.infrastructure.cleanup;

import com.vkplatform.iam.verification.application.service.EmailVerificationCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "iam.email.cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public final class EmailVerificationCleanupJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationCleanupJob.class);

    private final EmailVerificationCleanupService cleanupService;

    public EmailVerificationCleanupJob(EmailVerificationCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(
            initialDelayString = "${iam.email.cleanup-initial-delay:PT15M}",
            fixedDelayString = "${iam.email.cleanup-interval:PT15M}"
    )
    void cleanup() {
        int deleted = cleanupService.cleanupOnce();
        if (deleted > 0) {
            LOGGER.info("Deleted terminal email verification challenges; count={}", deleted);
        }
    }
}
