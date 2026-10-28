package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaAuthenticationSessionRepository implements AuthenticationSessionRepository {
    private final SpringDataAuthenticationSessionRepository sessions;

    JpaAuthenticationSessionRepository(SpringDataAuthenticationSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    @Transactional
    public AuthenticationSession create(AuthenticationSession session) {
        return sessions.save(new SessionEntity(session)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuthenticationSession> findById(UUID id) {
        return sessions.findById(id).map(SessionEntity::toDomain);
    }

    @Override
    @Transactional
    public boolean revokeOwned(
            UUID accountId,
            UUID sessionId,
            SessionRevokeReason reason,
            Instant revokedAt
    ) {
        SessionEntity entity = sessions.findLockedById(sessionId).orElse(null);
        if (entity == null || !entity.toDomain().accountId().equals(accountId)) {
            return false;
        }
        entity.apply(entity.toDomain().revoke(reason, revokedAt));
        return true;
    }

    @Override
    @Transactional
    public int revokeAll(
            UUID accountId,
            SessionRevokeReason reason,
            Instant revokedAt
    ) {
        List<SessionEntity> active = sessions.findLockedByAccountIdAndStatusOrderByIdAsc(
                accountId,
                SessionStatus.ACTIVE
        );
        active.forEach(entity -> entity.apply(entity.toDomain().revoke(reason, revokedAt)));
        return active.size();
    }
}
