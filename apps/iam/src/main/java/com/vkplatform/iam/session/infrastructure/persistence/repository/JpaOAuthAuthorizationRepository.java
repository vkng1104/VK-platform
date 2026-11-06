package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.OAuthAuthorizationRepository;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthAuthorizationEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JpaOAuthAuthorizationRepository implements OAuthAuthorizationRepository {
    private final SpringDataOAuthAuthorizationRepository authorizations;

    JpaOAuthAuthorizationRepository(SpringDataOAuthAuthorizationRepository authorizations) {
        this.authorizations = authorizations;
    }

    @Override
    @Transactional
    public void invalidate(
            String authorizationId,
            OAuthAuthorizationInvalidationReason reason,
            Instant invalidatedAt
    ) {
        authorizations.findLockedById(authorizationId)
                .ifPresent(authorization -> authorization.invalidate(invalidatedAt, reason));
    }

    @Override
    @Transactional
    public int invalidateAllForSession(
            UUID sessionId,
            OAuthAuthorizationInvalidationReason reason,
            Instant invalidatedAt
    ) {
        List<OAuthAuthorizationEntity> pending =
                authorizations.findLockedBySessionIdAndInvalidatedAtIsNull(sessionId);
        pending.forEach(authorization -> authorization.invalidate(invalidatedAt, reason));
        return pending.size();
    }
}
