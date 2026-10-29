package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.OAuthClientOperations;
import com.vkplatform.iam.session.api.OAuthClientRegistrationOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "iam.oauth.enabled", havingValue = "true")
public final class OAuthClientOperatorRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(OAuthClientOperatorRunner.class);

    private final OAuthClientProperties properties;
    private final OAuthClientRegistrationOperations registrations;
    private final OAuthClientOperations credentials;

    public OAuthClientOperatorRunner(
            OAuthClientProperties properties,
            OAuthClientRegistrationOperations registrations,
            OAuthClientOperations credentials
    ) {
        this.properties = properties;
        this.registrations = registrations;
        this.credentials = credentials;
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
            case "reconcile" -> registrations.reconcile(context);
            case "rotate-secret" -> credentials.rotateSecret(new RotateOAuthClientSecret(
                    properties.operatorClientId(),
                    properties.operatorClientSecret(),
                    properties.operatorClientSecretExpiresAt(),
                    context
            ));
            default -> throw new IllegalStateException("unsupported OAuth-client operator operation");
        };
        LOGGER.info(
                "Completed explicit OAuth-client operation; operation={}; result={}",
                operation,
                result
        );
    }
}
