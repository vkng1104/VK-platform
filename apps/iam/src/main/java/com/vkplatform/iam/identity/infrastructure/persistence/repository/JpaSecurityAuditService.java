package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.identity.api.command.RecordSecurityAuditEvent;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.SecurityEventEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Repository
public class JpaSecurityAuditService implements SecurityAuditOperations {
    private final SpringDataSecurityEventRepository events;
    private final JdbcTemplate jdbcTemplate;

    JpaSecurityAuditService(
            SpringDataSecurityEventRepository events,
            JdbcTemplate jdbcTemplate
    ) {
        this.events = events;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void record(RecordSecurityAuditEvent event) {
        Objects.requireNonNull(event);
        events.saveAndFlush(new SecurityEventEntity(
                UUID.randomUUID(),
                event.type(),
                event.accountId(),
                event.targetId(),
                event.actorId(),
                event.requestId(),
                event.occurredAt()
        ));
    }

    @Override
    @Transactional
    public int deleteRetainedBefore(Instant cutoff, int batchSize) {
        Objects.requireNonNull(cutoff);
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("security audit cleanup batch size is invalid");
        }
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                    FROM iam_identity.security_events
                    WHERE occurred_at < ?
                    ORDER BY occurred_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM iam_identity.security_events event
                USING candidates
                WHERE event.id = candidates.id
                """, Timestamp.from(cutoff), batchSize);
    }
}
