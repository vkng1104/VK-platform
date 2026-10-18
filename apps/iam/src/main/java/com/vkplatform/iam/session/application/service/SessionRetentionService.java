package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.session.application.port.out.SessionRetentionRepository;
import com.vkplatform.iam.session.infrastructure.config.SessionProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class SessionRetentionService {
    private final SessionRetentionRepository repository;
    private final SessionProperties properties;
    private final Clock clock;

    public SessionRetentionService(
            SessionRetentionRepository repository,
            SessionProperties properties,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    public SessionRetentionRepository.CleanupResult cleanupOnce() {
        Instant now = clock.instant();
        return repository.cleanup(
                now,
                now.minus(properties.sessionRetention()),
                now.minus(properties.authorizationRetention()),
                now.minus(properties.auditRetention()),
                properties.cleanupBatchSize()
        );
    }
}
