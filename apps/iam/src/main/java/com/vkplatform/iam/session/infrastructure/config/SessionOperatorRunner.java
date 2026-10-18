package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.OAuthClientOperations;
import com.vkplatform.iam.session.api.OAuthClientRegistrationOperations;
import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import com.vkplatform.iam.session.application.service.SessionRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public final class SessionOperatorRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionOperatorRunner.class);

    private final SessionProperties properties;
    private final SigningKeyOperations signingKeys;
    private final OAuthClientRegistrationOperations clientRegistration;
    private final OAuthClientOperations clientCredentials;
    private final SessionRetentionService retention;

    public SessionOperatorRunner(
            SessionProperties properties,
            SigningKeyOperations signingKeys,
            OAuthClientRegistrationOperations clientRegistration,
            OAuthClientOperations clientCredentials,
            SessionRetentionService retention
    ) {
        this.properties = properties;
        this.signingKeys = signingKeys;
        this.clientRegistration = clientRegistration;
        this.clientCredentials = clientCredentials;
        this.retention = retention;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String operation = properties.operatorOperation();
        if (operation == null || operation.isBlank()) {
            return;
        }
        OperatorContext context = new OperatorContext(
                properties.operatorActor(),
                properties.operatorOperationId()
        );
        Object result = switch (operation.strip()) {
            case "bootstrap-signing-key" -> signingKeys.bootstrap(context);
            case "rotate-signing-key" -> signingKeys.rotate(context);
            case "retire-signing-keys" -> signingKeys.retireExpired(context);
            case "reencrypt-signing-keys" -> signingKeys.reencrypt(properties.operatorBatchSize(), context);
            case "compromise-signing-key" -> signingKeys.compromise(properties.operatorKeyId(), context);
            case "reconcile-oauth-clients" -> clientRegistration.reconcile(context);
            case "cleanup-session-data" -> retention.cleanupOnce();
            case "rotate-oauth-client-secret" -> clientCredentials.rotateSecret(new RotateOAuthClientSecret(
                    properties.operatorClientId(),
                    properties.operatorClientSecret(),
                    properties.operatorClientSecretExpiresAt(),
                    context
            ));
            default -> throw new IllegalStateException("unsupported IAM session operator operation");
        };
        LOGGER.info("Completed explicit IAM session operator operation; operation={}; result={}", operation, result);
    }
}
