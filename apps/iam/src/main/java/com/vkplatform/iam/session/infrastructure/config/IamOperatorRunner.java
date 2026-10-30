package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.session.api.IamMaintenanceOperations;
import com.vkplatform.iam.session.api.OAuthClientOperations;
import com.vkplatform.iam.session.api.OAuthClientRegistrationOperations;
import com.vkplatform.iam.session.api.SigningKeyOperations;
import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = {"iam.session.enabled", "iam.oauth.enabled"},
        havingValue = "true"
)
public final class IamOperatorRunner implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(IamOperatorRunner.class);

    private final IamOperationsProperties properties;
    private final SigningKeyOperations signingKeys;
    private final OAuthClientRegistrationOperations registrations;
    private final OAuthClientOperations credentials;
    private final IamMaintenanceOperations maintenance;
    private final IamSecurityTelemetry telemetry;

    public IamOperatorRunner(
            IamOperationsProperties properties,
            SigningKeyOperations signingKeys,
            OAuthClientRegistrationOperations registrations,
            OAuthClientOperations credentials,
            IamMaintenanceOperations maintenance,
            IamSecurityTelemetry telemetry
    ) {
        this.properties = properties;
        this.signingKeys = signingKeys;
        this.registrations = registrations;
        this.credentials = credentials;
        this.maintenance = maintenance;
        this.telemetry = telemetry;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        String operation = properties.operatorOperation();
        if (operation == null || operation.isBlank()) {
            return;
        }
        String normalized = operation.strip();
        validateArguments(normalized);
        long startedAt = telemetry.start();
        OperatorContext context = new OperatorContext(
                properties.operatorActor(),
                properties.operatorOperationId()
        );
        IamSecurityTelemetry.Operation metricOperation = metricOperation(normalized);
        try {
            Object result = switch (normalized) {
                case "bootstrap-signing-key" -> signingKeys.bootstrap(context);
                case "rotate-signing-key" -> signingKeys.rotate(context);
                case "retire-signing-keys" -> signingKeys.retireExpired(context);
                case "reencrypt-signing-keys" -> signingKeys.reencrypt(
                        properties.operatorBatchSize(),
                        context
                );
                case "compromise-signing-key" -> signingKeys.compromise(
                        properties.operatorKeyId(),
                        context
                );
                case "reconcile-oauth-clients" -> registrations.reconcile(context);
                case "rotate-oauth-client-secret" -> credentials.rotateSecret(
                        new RotateOAuthClientSecret(
                                properties.operatorClientId(),
                                properties.operatorClientSecret(),
                                properties.operatorClientSecretExpiresAt(),
                                context
                        )
                );
                case "cleanup-iam-data" -> maintenance.cleanup(context);
                default -> throw new IllegalStateException("unsupported IAM operator operation");
            };
            LOGGER.info(
                    "Completed explicit IAM operation; operation={}; result={}",
                    normalized,
                    result
            );
            telemetry.recordOperator(
                    metricOperation,
                    IamSecurityTelemetry.Outcome.SUCCESS,
                    startedAt
            );
        } catch (RuntimeException failure) {
            telemetry.recordOperator(
                    metricOperation,
                    IamSecurityTelemetry.Outcome.FAILURE,
                    startedAt
            );
            throw failure;
        }
    }

    private void validateArguments(String operation) {
        switch (operation) {
            case "compromise-signing-key" -> requireText(
                    properties.operatorKeyId(),
                    "IAM_OPERATOR_KEY_ID"
            );
            case "rotate-oauth-client-secret" -> {
                requireText(properties.operatorClientId(), "IAM_OPERATOR_CLIENT_ID");
                requireText(properties.operatorClientSecret(), "IAM_OPERATOR_CLIENT_SECRET");
            }
            case "bootstrap-signing-key",
                    "rotate-signing-key",
                    "retire-signing-keys",
                    "reencrypt-signing-keys",
                    "reconcile-oauth-clients",
                    "cleanup-iam-data" -> {
            }
            default -> throw new IllegalStateException("unsupported IAM operator operation");
        }
    }

    private static IamSecurityTelemetry.Operation metricOperation(String operation) {
        return switch (operation) {
            case "bootstrap-signing-key" ->
                    IamSecurityTelemetry.Operation.OPERATOR_BOOTSTRAP_SIGNING_KEY;
            case "rotate-signing-key" ->
                    IamSecurityTelemetry.Operation.OPERATOR_ROTATE_SIGNING_KEY;
            case "retire-signing-keys" ->
                    IamSecurityTelemetry.Operation.OPERATOR_RETIRE_SIGNING_KEYS;
            case "reencrypt-signing-keys" ->
                    IamSecurityTelemetry.Operation.OPERATOR_REENCRYPT_SIGNING_KEYS;
            case "compromise-signing-key" ->
                    IamSecurityTelemetry.Operation.OPERATOR_COMPROMISE_SIGNING_KEY;
            case "reconcile-oauth-clients" ->
                    IamSecurityTelemetry.Operation.OPERATOR_RECONCILE_OAUTH_CLIENTS;
            case "rotate-oauth-client-secret" ->
                    IamSecurityTelemetry.Operation.OPERATOR_ROTATE_OAUTH_CLIENT_SECRET;
            case "cleanup-iam-data" -> IamSecurityTelemetry.Operation.OPERATOR_CLEANUP;
            default -> throw new IllegalStateException("unsupported IAM operator operation");
        };
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the selected operation");
        }
    }
}
