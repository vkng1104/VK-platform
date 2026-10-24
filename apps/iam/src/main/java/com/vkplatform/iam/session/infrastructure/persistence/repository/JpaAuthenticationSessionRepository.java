package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import org.springframework.stereotype.Repository;

@Repository
public class JpaAuthenticationSessionRepository implements AuthenticationSessionRepository {
    private final SpringDataAuthenticationSessionRepository sessions;

    JpaAuthenticationSessionRepository(SpringDataAuthenticationSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Override
    public AuthenticationSession create(AuthenticationSession session) {
        return sessions.save(new SessionEntity(session)).toDomain();
    }
}
