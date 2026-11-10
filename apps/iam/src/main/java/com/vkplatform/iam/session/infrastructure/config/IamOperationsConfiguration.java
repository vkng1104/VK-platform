package com.vkplatform.iam.session.infrastructure.config;

import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.session.api.IamMaintenanceOperations;
import com.vkplatform.iam.session.application.port.out.IamRetentionRepository;
import com.vkplatform.iam.session.application.service.IamRetentionService;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
import com.vkplatform.iam.session.domain.IamRetentionPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class IamOperationsConfiguration {
    @Bean
    IamRetentionPolicy iamRetentionPolicy(IamOperationsProperties properties) {
        return new IamRetentionPolicy(
                properties.sessionRetention(),
                properties.authorizationRetention(),
                properties.auditRetention(),
                properties.signingKeyRetention(),
                properties.cleanupBatchSize()
        );
    }

    @Bean
    IamMaintenanceOperations iamMaintenanceOperations(
            IamRetentionRepository repository,
            SecurityAuditOperations audit,
            IamRetentionPolicy policy,
            IamSecurityTelemetry telemetry,
            Clock clock
    ) {
        return new IamRetentionService(repository, audit, policy, telemetry, clock);
    }
}
