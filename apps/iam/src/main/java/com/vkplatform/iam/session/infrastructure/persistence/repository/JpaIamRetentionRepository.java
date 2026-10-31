package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.api.command.OperatorContext;
import com.vkplatform.iam.session.api.result.IamRetentionResult;
import com.vkplatform.iam.session.application.port.out.IamRetentionRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaIamRetentionRepository implements IamRetentionRepository {
    private static final String OPERATION = "cleanup-iam-data";

    private final JdbcTemplate jdbcTemplate;

    JpaIamRetentionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void lockOperation(String operationId) {
        jdbcTemplate.execute(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (PreparedStatementCallback<Void>) statement -> {
                    statement.setString(1, operationId);
                    statement.execute();
                    return null;
                }
        );
    }

    @Override
    public Optional<StoredRetentionOperation> findOperation(String operationId) {
        List<StoredRetentionOperation> operations = jdbcTemplate.query("""
                SELECT operation_type, actor_id, result_details
                FROM iam_session.operator_operations
                WHERE operation_id = ?
                """, (result, row) -> new StoredRetentionOperation(
                result.getString("operation_type"),
                result.getString("actor_id"),
                result.getString("result_details") == null
                        ? null
                        : IamRetentionResult.fromStoredValue(result.getString("result_details"))
        ), operationId);
        return operations.stream().findFirst();
    }

    @Override
    public IamRetentionResult cleanup(
            RetentionCutoffs cutoffs,
            Instant now,
            int batchSize
    ) {
        Objects.requireNonNull(cutoffs);
        Objects.requireNonNull(now);
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("retention cleanup batch size is invalid");
        }

        List<UUID> expiredSessions = expireSessions(now, batchSize);
        int invalidated = invalidateAuthorizations(expiredSessions, now)
                + expireSessionlessAuthorizations(now, batchSize);
        int deletedRefreshTokens = deleteRefreshLineage(
                cutoffs.authorizationCutoff(),
                batchSize
        );
        int deletedAuthorizations = deleteAuthorizations(
                cutoffs.authorizationCutoff(),
                batchSize
        );
        int deletedSessions = deleteSessions(cutoffs.sessionCutoff(), batchSize);
        int deletedAuditEvents = deleteLifecycleEvents(cutoffs.auditCutoff(), batchSize);
        int deletedSigningKeys = deleteSigningKeys(
                cutoffs.signingKeyCutoff(),
                batchSize
        );
        return new IamRetentionResult(
                expiredSessions.size(),
                invalidated,
                deletedRefreshTokens,
                deletedAuthorizations,
                deletedSessions,
                deletedAuditEvents,
                deletedSigningKeys
        );
    }

    @Override
    public void complete(
            OperatorContext context,
            IamRetentionResult result,
            Instant completedAt
    ) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO iam_session.operator_operations (
                    operation_id, operation_type, actor_id, result_count,
                    result_details, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                context.operationId(),
                OPERATION,
                context.actorId(),
                result.totalChanges(),
                result.storedValue(),
                Timestamp.from(completedAt)
        );
        if (inserted != 1) {
            throw new IllegalStateException("retention operation result was not stored");
        }
    }

    private List<UUID> expireSessions(Instant now, int batchSize) {
        return jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT id
                    FROM iam_session.sessions
                    WHERE status = 'active' AND expires_at <= ?
                    ORDER BY expires_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE iam_session.sessions session_record
                SET status = 'revoked',
                    revoked_at = ?,
                    revoke_reason = 'expired',
                    version = version + 1
                FROM candidates
                WHERE session_record.id = candidates.id
                RETURNING session_record.id
                """,
                (result, row) -> result.getObject("id", UUID.class),
                Timestamp.from(now),
                batchSize,
                Timestamp.from(now)
        );
    }

    private int invalidateAuthorizations(List<UUID> sessionIds, Instant now) {
        int invalidated = 0;
        for (UUID sessionId : sessionIds) {
            invalidated += jdbcTemplate.update("""
                    UPDATE iam_session.oauth_authorizations
                    SET invalidated_at = ?,
                        invalidation_reason = 'expired',
                        version = version + 1
                    WHERE session_id = ? AND invalidated_at IS NULL
                    """, Timestamp.from(now), sessionId);
        }
        return invalidated;
    }

    private int expireSessionlessAuthorizations(Instant now, int batchSize) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                    FROM iam_session.oauth_authorizations
                    WHERE session_id IS NULL
                      AND invalidated_at IS NULL
                      AND access_token_expires_at <= ?
                    ORDER BY access_token_expires_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE iam_session.oauth_authorizations authorization_record
                SET invalidated_at = ?,
                    invalidation_reason = 'expired',
                    version = version + 1
                FROM candidates
                WHERE authorization_record.id = candidates.id
                """, Timestamp.from(now), batchSize, Timestamp.from(now));
    }

    private int deleteRefreshLineage(Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT authorization_record.id
                    FROM iam_session.oauth_authorizations authorization_record
                    WHERE authorization_record.invalidated_at < ?
                      AND EXISTS (
                          SELECT 1
                          FROM iam_session.oauth_refresh_tokens token
                          WHERE token.authorization_id = authorization_record.id
                      )
                    ORDER BY authorization_record.invalidated_at, authorization_record.id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM iam_session.oauth_refresh_tokens token
                USING candidates
                WHERE token.authorization_id = candidates.id
                """, Timestamp.from(cutoff), batchSize);
    }

    private int deleteAuthorizations(Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT authorization_record.id
                    FROM iam_session.oauth_authorizations authorization_record
                    WHERE authorization_record.invalidated_at < ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM iam_session.oauth_refresh_tokens token
                          WHERE token.authorization_id = authorization_record.id
                      )
                    ORDER BY authorization_record.invalidated_at, authorization_record.id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM iam_session.oauth_authorizations authorization_record
                USING candidates
                WHERE authorization_record.id = candidates.id
                """, Timestamp.from(cutoff), batchSize);
    }

    private int deleteSessions(Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT session_record.id
                    FROM iam_session.sessions session_record
                    WHERE session_record.status <> 'active'
                      AND COALESCE(session_record.compromised_at, session_record.revoked_at) < ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM iam_session.oauth_authorizations authorization_record
                          WHERE authorization_record.session_id = session_record.id
                      )
                    ORDER BY COALESCE(
                        session_record.compromised_at,
                        session_record.revoked_at
                    ), session_record.id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM iam_session.sessions session_record
                USING candidates
                WHERE session_record.id = candidates.id
                """, Timestamp.from(cutoff), batchSize);
    }

    private int deleteLifecycleEvents(Instant cutoff, int batchSize) {
        return deleteSigningKeyEvents(cutoff, batchSize)
                + deleteOAuthClientEvents(cutoff, batchSize);
    }

    private int deleteSigningKeyEvents(Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                    FROM iam_session.signing_key_events
                    WHERE occurred_at < ?
                    ORDER BY occurred_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM iam_session.signing_key_events event_record
                USING candidates
                WHERE event_record.id = candidates.id
                """, Timestamp.from(cutoff), batchSize);
    }

    private int deleteOAuthClientEvents(Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT id
                    FROM iam_session.oauth_client_events
                    WHERE occurred_at < ?
                    ORDER BY occurred_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM iam_session.oauth_client_events event_record
                USING candidates
                WHERE event_record.id = candidates.id
                """, Timestamp.from(cutoff), batchSize);
    }

    private int deleteSigningKeys(Instant cutoff, int batchSize) {
        return jdbcTemplate.update("""
                WITH candidates AS (
                    SELECT key_record.id
                    FROM iam_session.signing_keys key_record
                    WHERE key_record.status IN ('RETIRED', 'COMPROMISED')
                      AND COALESCE(key_record.retired_at, key_record.compromised_at) < ?
                      AND NOT EXISTS (
                          SELECT 1
                          FROM iam_session.signing_key_events event_record
                          WHERE event_record.key_id = key_record.id
                      )
                    ORDER BY COALESCE(
                        key_record.retired_at,
                        key_record.compromised_at
                    ), key_record.id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                DELETE FROM iam_session.signing_keys key_record
                USING candidates
                WHERE key_record.id = candidates.id
                """, Timestamp.from(cutoff), batchSize);
    }
}
