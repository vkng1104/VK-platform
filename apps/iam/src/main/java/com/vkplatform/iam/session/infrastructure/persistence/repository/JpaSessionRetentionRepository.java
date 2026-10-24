package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.SessionRetentionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "iam.session.enabled", havingValue = "true")
public class JpaSessionRetentionRepository implements SessionRetentionRepository {
    private final EntityManager entityManager;

    public JpaSessionRetentionRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public CleanupResult cleanup(
            Instant now,
            Instant sessionCutoff,
            Instant authorizationCutoff,
            Instant auditCutoff,
            int batchSize
    ) {
        int expiredSessions = expireSessions(now, batchSize);
        int deletedAuthorizations = deleteAuthorizations(authorizationCutoff, batchSize);
        int deletedSessions = deleteSessions(sessionCutoff, batchSize);
        int deletedAuditEvents = deleteAuditEvents(auditCutoff, batchSize);
        int deletedSigningKeys = deleteSigningKeys(auditCutoff, batchSize);
        return new CleanupResult(
                expiredSessions,
                deletedAuthorizations,
                deletedSessions,
                deletedAuditEvents,
                deletedSigningKeys
        );
    }

    private int expireSessions(Instant now, int batchSize) {
        List<UUID> ids = entityManager.createNativeQuery("""
                        SELECT id
                        FROM iam_session.sessions
                        WHERE status = 'active' AND expires_at <= :now
                        ORDER BY expires_at, id
                        FOR UPDATE SKIP LOCKED
                        """, UUID.class)
                .setParameter("now", now)
                .setMaxResults(batchSize)
                .getResultList();
        for (UUID id : ids) {
            entityManager.createNativeQuery("""
                            UPDATE iam_session.sessions
                            SET status = 'revoked', revoked_at = :now, revoke_reason = 'expired', version = version + 1
                            WHERE id = :id AND status = 'active'
                            """)
                    .setParameter("now", now)
                    .setParameter("id", id)
                    .executeUpdate();
            entityManager.createNativeQuery("""
                            UPDATE iam_session.oauth_authorizations
                            SET invalidated_at = :now,
                                invalidation_reason = 'expired',
                                version = version + 1
                            WHERE session_id = :id AND invalidated_at IS NULL
                            """)
                    .setParameter("now", now)
                    .setParameter("id", id)
                    .executeUpdate();
        }
        return ids.size();
    }

    private int deleteAuthorizations(Instant cutoff, int batchSize) {
        @SuppressWarnings("unchecked")
        List<String> ids = entityManager.createNativeQuery("""
                        SELECT id
                        FROM iam_session.oauth_authorizations
                        WHERE invalidated_at <= :cutoff
                           OR (
                                invalidated_at IS NULL
                                AND GREATEST(
                                    authorization_code_expires_at,
                                    access_token_expires_at,
                                    refresh_token_expires_at
                                ) <= :cutoff
                           )
                        ORDER BY COALESCE(
                            invalidated_at,
                            GREATEST(
                                authorization_code_expires_at,
                                access_token_expires_at,
                                refresh_token_expires_at
                            )
                        ), id
                        FOR UPDATE SKIP LOCKED
                        """)
                .setParameter("cutoff", cutoff)
                .setMaxResults(batchSize)
                .getResultList();
        for (String id : ids) {
            entityManager.createNativeQuery(
                            "DELETE FROM iam_session.oauth_refresh_tokens WHERE authorization_id = :id")
                    .setParameter("id", id)
                    .executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM iam_session.oauth_authorizations WHERE id = :id")
                    .setParameter("id", id)
                    .executeUpdate();
        }
        return ids.size();
    }

    private int deleteSessions(Instant cutoff, int batchSize) {
        return entityManager.createNativeQuery("""
                        DELETE FROM iam_session.sessions
                        WHERE id IN (
                            SELECT session.id
                            FROM iam_session.sessions session
                            WHERE session.status <> 'active'
                              AND COALESCE(session.compromised_at, session.revoked_at, session.expires_at) <= :cutoff
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM iam_session.oauth_authorizations candidate_authorization
                                  WHERE candidate_authorization.session_id = session.id
                              )
                            ORDER BY COALESCE(
                                session.compromised_at,
                                session.revoked_at,
                                session.expires_at
                            ), session.id
                            LIMIT :batchSize
                        )
                        """)
                .setParameter("cutoff", cutoff)
                .setParameter("batchSize", batchSize)
                .executeUpdate();
    }

    private int deleteAuditEvents(Instant cutoff, int batchSize) {
        int deleted = deleteEvents("iam_identity.security_events", cutoff, batchSize);
        deleted += deleteEvents("iam_session.oauth_client_events", cutoff, batchSize);
        deleted += deleteEvents("iam_session.signing_key_events", cutoff, batchSize);
        return deleted;
    }

    private int deleteEvents(String table, Instant cutoff, int batchSize) {
        return entityManager.createNativeQuery("""
                        DELETE FROM %s
                        WHERE id IN (
                            SELECT id FROM %s
                            WHERE occurred_at <= :cutoff
                            ORDER BY occurred_at, id
                            LIMIT :batchSize
                        )
                        """.formatted(table, table))
                .setParameter("cutoff", cutoff)
                .setParameter("batchSize", batchSize)
                .executeUpdate();
    }

    private int deleteSigningKeys(Instant cutoff, int batchSize) {
        return entityManager.createNativeQuery("""
                        DELETE FROM iam_session.signing_keys key
                        WHERE key.id IN (
                            SELECT candidate.id
                            FROM iam_session.signing_keys candidate
                            WHERE candidate.status IN ('RETIRED', 'COMPROMISED')
                              AND candidate.retired_at <= :cutoff
                              AND NOT EXISTS (
                                  SELECT 1 FROM iam_session.signing_key_events event
                                  WHERE event.key_id = candidate.id
                              )
                            ORDER BY candidate.retired_at, candidate.id
                            LIMIT :batchSize
                        )
                        """)
                .setParameter("cutoff", cutoff)
                .setParameter("batchSize", batchSize)
                .executeUpdate();
    }
}
