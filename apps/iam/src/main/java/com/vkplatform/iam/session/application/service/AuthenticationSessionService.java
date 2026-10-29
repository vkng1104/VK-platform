package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.api.AuthenticationSessionOperations;
import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.api.command.RevokeAllAuthenticationSessions;
import com.vkplatform.iam.session.api.command.RevokeAuthenticationSession;
import com.vkplatform.iam.session.api.result.AuthenticationSessionPage;
import com.vkplatform.iam.session.api.result.AuthenticationSessionSummary;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionPolicy;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class AuthenticationSessionService implements AuthenticationSessionOperations {
    private static final int MAX_CONTEXT_LENGTH = 128;
    private final PasswordlessIdentityOperations identities;
    private final AuthenticationSessionRepository sessions;
    private final SessionPolicy policy;
    private final Clock clock;

    public AuthenticationSessionService(
            PasswordlessIdentityOperations identities,
            AuthenticationSessionRepository sessions,
            SessionPolicy policy,
            Clock clock
    ) {
        this.identities = Objects.requireNonNull(identities);
        this.sessions = Objects.requireNonNull(sessions);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public EstablishedAuthenticationSession exchange(ExchangeAuthenticationGrant command) {
        Objects.requireNonNull(command);
        AuthenticatedAccount account = identities.consumeSignInGrant(new ConsumeSignInGrant(
                command.grant(),
                command.audience(),
                command.requestId()
        ));
        Instant now = clock.instant();
        AuthenticationSession session = AuthenticationSession.create(
                UUID.randomUUID(),
                account.accountId(),
                policy.bffClientId(),
                now,
                now.plus(policy.absoluteTtl())
        );
        sessions.create(session);
        return new EstablishedAuthenticationSession(account.accountId(), session);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticationSessionSummary current(UUID accountId, UUID sessionId) {
        AuthenticationSession session = sessions.findOwned(
                Objects.requireNonNull(accountId),
                Objects.requireNonNull(sessionId)
        ).orElseThrow(SessionFailure::sessionUnavailable);
        if (!session.canAuthorize(accountId, policy.bffClientId(), clock.instant())) {
            throw SessionFailure.sessionUnavailable();
        }
        return summary(session);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthenticationSessionPage sessions(
            UUID accountId,
            UUID currentSessionId,
            int page,
            int size
    ) {
        current(accountId, currentSessionId);
        if (page < 0 || size < 1 || size > 100) {
            throw SessionFailure.invalidRequest();
        }
        AuthenticationSessionRepository.SessionPage result = sessions.findActiveByAccount(
                accountId,
                clock.instant(),
                page,
                size
        );
        return new AuthenticationSessionPage(
                result.sessions().stream().map(AuthenticationSessionService::summary).toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages()
        );
    }

    @Override
    @Transactional
    public void revoke(RevokeAuthenticationSession command) {
        Objects.requireNonNull(command);
        requireContext(command.requestId());
        AuthenticationSessionRepository.RevokeResult result = sessions.revokeOwnedFromCurrent(
                command.accountId(),
                command.currentSessionId(),
                Objects.requireNonNull(command.targetSessionId()),
                policy.bffClientId(),
                SessionRevokeReason.USER_REQUEST,
                clock.instant()
        );
        if (result == AuthenticationSessionRepository.RevokeResult.CURRENT_UNAVAILABLE) {
            throw SessionFailure.sessionUnavailable();
        }
        if (result == AuthenticationSessionRepository.RevokeResult.TARGET_NOT_FOUND) {
            throw SessionFailure.sessionNotFound();
        }
    }

    @Override
    @Transactional
    public void logout(RevokeAuthenticationSession command) {
        Objects.requireNonNull(command);
        requireContext(command.requestId());
        if (!Objects.equals(command.currentSessionId(), command.targetSessionId())) {
            throw SessionFailure.invalidRequest();
        }
        sessions.revokeOwnedAndInvalidate(
                Objects.requireNonNull(command.accountId()),
                Objects.requireNonNull(command.targetSessionId()),
                SessionRevokeReason.LOGOUT,
                clock.instant()
        );
    }

    @Override
    @Transactional
    public int revokeAll(RevokeAllAuthenticationSessions command) {
        Objects.requireNonNull(command);
        requireContext(command.requestId());
        AuthenticationSessionRepository.RevokeAllResult result = sessions.revokeAllFromCurrent(
                command.accountId(),
                command.currentSessionId(),
                policy.bffClientId(),
                SessionRevokeReason.ALL_SESSIONS_REVOKED,
                clock.instant()
        );
        if (!result.currentAvailable()) {
            throw SessionFailure.sessionUnavailable();
        }
        return result.revokedCount();
    }

    private static AuthenticationSessionSummary summary(AuthenticationSession session) {
        return new AuthenticationSessionSummary(
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
