package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "iam.oauth.enabled", havingValue = "true")
public final class SigningKeyOperatorRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SigningKeyOperatorRunner.class);

    private final SigningKeyProperties properties;
    private final SigningKeyOperations signingKeys;

    public SigningKeyOperatorRunner(
            SigningKeyProperties properties,
            SigningKeyOperations signingKeys
    ) {
        this.properties = properties;
        this.signingKeys = signingKeys;
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
            case "bootstrap" -> signingKeys.bootstrap(context);
            case "rotate" -> signingKeys.rotate(context);
            case "retire-expired" -> signingKeys.retireExpired(context);
            case "reencrypt" -> signingKeys.reencrypt(properties.operatorBatchSize(), context);
            case "compromise" -> signingKeys.compromise(properties.operatorKeyId(), context);
            default -> throw new IllegalStateException("unsupported signing-key operator operation");
        };
        LOGGER.info(
                "Completed explicit signing-key operation; operation={}; result={}",
                operation,
                result
        );
    }
}
