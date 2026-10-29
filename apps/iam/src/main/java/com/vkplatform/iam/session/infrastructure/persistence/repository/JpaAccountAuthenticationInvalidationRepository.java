package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.application.port.out.AccountAuthenticationInvalidationRepository;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public class JpaAccountAuthenticationInvalidationRepository
        implements AccountAuthenticationInvalidationRepository {
    private final SpringDataAuthenticationSessionRepository sessions;
    private final SpringDataOAuthAuthorizationRepository authorizations;

    JpaAccountAuthenticationInvalidationRepository(
            SpringDataAuthenticationSessionRepository sessions,
            SpringDataOAuthAuthorizationRepository authorizations
    ) {
        this.sessions = sessions;
        this.authorizations = authorizations;
    }

    @Override
    @Transactional
    public void invalidateAccount(UUID accountId, Instant occurredAt) {
        for (SessionEntity session : sessions.findLockedByAccountIdOrderByIdAsc(accountId)) {
            Instant transitionAt = occurredAt.isBefore(session.toDomain().lastRefreshedAt())
                    ? session.toDomain().lastRefreshedAt()
                    : occurredAt;
            session.apply(session.toDomain().revoke(
                    SessionRevokeReason.ACCOUNT_STATE_CHANGED,
                    transitionAt
            ));
            authorizations.findLockedBySessionIdAndInvalidatedAtIsNull(session.id())
                    .forEach(authorization -> authorization.invalidate(
                            transitionAt,
                            OAuthAuthorizationInvalidationReason.ACCOUNT_STATE_CHANGED
                    ));
        }
    }
}
