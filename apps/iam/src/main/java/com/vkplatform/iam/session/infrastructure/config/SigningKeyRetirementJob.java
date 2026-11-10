package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@ConditionalOnProperty(
        name = {"iam.oauth.enabled", "iam.signing-keys.retirement-enabled"},
        havingValue = "true"
)
public final class SigningKeyRetirementJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(SigningKeyRetirementJob.class);

    private final SigningKeyOperations signingKeys;
    private final Clock clock;

    public SigningKeyRetirementJob(SigningKeyOperations signingKeys, Clock clock) {
        this.signingKeys = signingKeys;
        this.clock = clock;
    }

    @Scheduled(
            initialDelayString = "${iam.signing-keys.retirement-initial-delay:PT1M}",
            fixedDelayString = "${iam.signing-keys.retirement-interval:PT5M}"
    )
    void retireExpired() {
        String operationId = "scheduled-signing-key-retirement-" + clock.instant();
        int retired = signingKeys.retireExpired(new OperatorContext(
                "iam-signing-key-scheduler",
                operationId
        ));
        if (retired > 0) {
            LOGGER.info("Retired signing keys after their publication window; count={}", retired);
        }
    }
}
