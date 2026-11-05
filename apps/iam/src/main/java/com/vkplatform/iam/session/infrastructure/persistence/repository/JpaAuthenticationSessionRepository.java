package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    public List<AuthenticationSession> findAllLockedByAccountId(UUID accountId) {
        return sessions.findLockedByAccountIdOrderByIdAsc(accountId).stream()
                .map(SessionEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public Optional<AuthenticationSession> findLockedById(UUID id) {
        return sessions.findLockedById(id).map(SessionEntity::toDomain);
    }

    @Override
    @Transactional
    public void recordRevocation(AuthenticationSession revoked) {
        applyLocked(revoked, "session to revoke does not exist");
    }

    @Override
    @Transactional
    public void recordRefresh(AuthenticationSession refreshed) {
        applyLocked(refreshed, "session to refresh does not exist");
    }

    @Override
    @Transactional
    public void recordCompromise(AuthenticationSession compromised) {
        applyLocked(compromised, "session to compromise does not exist");
    }

    private void applyLocked(AuthenticationSession session, String missingMessage) {
        sessions.findLockedById(session.id())
                .orElseThrow(() -> new IllegalStateException(missingMessage))
                .apply(session);
    }


}
