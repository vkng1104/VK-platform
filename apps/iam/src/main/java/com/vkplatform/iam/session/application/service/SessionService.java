package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.api.SessionOperations;
import com.vkplatform.iam.session.api.command.ExchangeSignInGrant;
import com.vkplatform.iam.session.api.command.RevokeAllSessions;
import com.vkplatform.iam.session.api.command.RevokeSession;
import com.vkplatform.iam.session.api.result.EstablishedSession;
import com.vkplatform.iam.session.api.result.SessionSummary;
import com.vkplatform.iam.session.application.port.out.SessionRepository;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.domain.SessionPolicy;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.UserSession;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class SessionService implements SessionOperations {
    private static final int MAX_CONTEXT_LENGTH = 128;

    private final PasswordlessIdentityOperations identities;
    private final SessionRepository repository;
    private final SessionPolicy policy;
    private final Clock clock;

    public SessionService(
            PasswordlessIdentityOperations identities,
            SessionRepository repository,
            SessionPolicy policy,
            Clock clock
    ) {
        this.identities = Objects.requireNonNull(identities);
        this.repository = Objects.requireNonNull(repository);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public EstablishedSession exchange(ExchangeSignInGrant command) {
        Objects.requireNonNull(command);
        String requestId = requireContext(command.requestId());
        if (!policy.authenticationAudience().equals(command.audience())) {
            throw SessionFailure.invalidRequest();
        }

        AuthenticatedAccount account = identities.consumeSignInGrant(new ConsumeSignInGrant(
                command.grant(),
                command.audience(),
                requestId
        ));
        Instant now = clock.instant();
        UUID sessionId = UUID.randomUUID();
        Instant sessionExpiresAt = now.plus(policy.absoluteSessionTtl());
        UserSession session = UserSession.create(
                sessionId,
                account.accountId(),
                policy.bffClientId(),
                now,
                sessionExpiresAt
        );
        repository.create(session, requestId);
        return new EstablishedSession(account.accountId(), summary(session));
    }

    @Override
    @Transactional(readOnly = true)
    public SessionSummary session(UUID accountId, UUID sessionId) {
        UserSession session = repository.findOwned(
                Objects.requireNonNull(accountId),
                Objects.requireNonNull(sessionId)
        );
        if (!session.canUse(session.clientId(), clock.instant())) {
            throw SessionFailure.sessionUnavailable();
        }
        return summary(session);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionSummary> sessions(UUID accountId) {
        return repository.findActiveByAccount(Objects.requireNonNull(accountId), clock.instant())
                .stream()
                .map(SessionService::summary)
                .toList();
    }

    @Override
    @Transactional
    public void revoke(RevokeSession command) {
        revoke(command, SessionRevokeReason.USER_REQUEST);
    }

    @Override
    @Transactional
    public void logout(RevokeSession command) {
        revoke(command, SessionRevokeReason.LOGOUT);
    }

    private void revoke(RevokeSession command, SessionRevokeReason reason) {
        Objects.requireNonNull(command);
        boolean revoked = repository.revokeOwned(
                Objects.requireNonNull(command.accountId()),
                Objects.requireNonNull(command.sessionId()),
                reason,
                clock.instant(),
                requireContext(command.requestId())
        );
        if (!revoked) {
            throw SessionFailure.sessionNotFound();
        }
    }

    @Override
    @Transactional
    public int revokeAll(RevokeAllSessions command) {
        Objects.requireNonNull(command);
        return repository.revokeAll(
                Objects.requireNonNull(command.accountId()),
                command.exceptSessionId(),
                command.accountId().toString(),
                SessionRevokeReason.ALL_SESSIONS_REVOKED,
                clock.instant(),
                requireContext(command.requestId())
        );
    }

    private static SessionSummary summary(UserSession session) {
        return new SessionSummary(
                session.id(),
                session.clientId(),
                session.status().wireValue(),
                session.createdAt(),
                session.lastRefreshedAt(),
                session.expiresAt(),
                session.revokedAt(),
                session.compromisedAt(),
                session.revokeReason() == null ? null : session.revokeReason().wireValue()
        );
    }

    private static String requireContext(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_CONTEXT_LENGTH) {
            throw SessionFailure.invalidRequest();
        }
        return value.strip();
    }

}
