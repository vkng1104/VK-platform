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
import com.vkplatform.iam.session.application.port.out.OAuthAuthorizationRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.OAuthAuthorizationInvalidationReason;
import com.vkplatform.iam.session.domain.SessionFailure;
import com.vkplatform.iam.session.domain.SessionPolicy;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import com.vkplatform.iam.session.domain.SessionStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class AuthenticationSessionService implements AuthenticationSessionOperations {
    private static final int MAX_CONTEXT_LENGTH = 128;
    private final PasswordlessIdentityOperations identities;
    private final AuthenticationSessionRepository sessions;
    private final OAuthAuthorizationRepository authorizations;
    private final SessionPolicy policy;
    private final Clock clock;

    public AuthenticationSessionService(
            PasswordlessIdentityOperations identities,
            AuthenticationSessionRepository sessions,
            OAuthAuthorizationRepository authorizations,
            SessionPolicy policy,
            Clock clock
    ) {
        this.identities = Objects.requireNonNull(identities);
        this.sessions = Objects.requireNonNull(sessions);
        this.authorizations = Objects.requireNonNull(authorizations);
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
        UUID accountId = Objects.requireNonNull(command.accountId());
        UUID targetSessionId = Objects.requireNonNull(command.targetSessionId());
        Instant now = clock.instant();
        List<AuthenticationSession> owned = sessions.findAllLockedByAccountId(accountId);
        requireUsableCurrent(owned, accountId, command.currentSessionId(), now);
        AuthenticationSession target = findSession(owned, targetSessionId);
        if (target == null) {
            throw SessionFailure.sessionNotFound();
        }
        revokeAndInvalidate(target, SessionRevokeReason.USER_REQUEST, now);
    }

    @Override
    @Transactional
    public void logout(RevokeAuthenticationSession command) {
        Objects.requireNonNull(command);
        requireContext(command.requestId());
        if (!Objects.equals(command.currentSessionId(), command.targetSessionId())) {
            throw SessionFailure.invalidRequest();
        }
        UUID accountId = Objects.requireNonNull(command.accountId());
        sessions.findLockedById(Objects.requireNonNull(command.targetSessionId()))
                .filter(session -> session.accountId().equals(accountId))
                .ifPresent(session -> revokeAndInvalidate(
                        session,
                        SessionRevokeReason.LOGOUT,
                        clock.instant()
                ));
    }

    @Override
    @Transactional
    public int revokeAll(RevokeAllAuthenticationSessions command) {
        Objects.requireNonNull(command);
        requireContext(command.requestId());
        UUID accountId = Objects.requireNonNull(command.accountId());
        Instant now = clock.instant();
        List<AuthenticationSession> owned = sessions.findAllLockedByAccountId(accountId);
        requireUsableCurrent(owned, accountId, command.currentSessionId(), now);
        int revoked = 0;
        for (AuthenticationSession session : owned) {
            if (session.status() == SessionStatus.ACTIVE) {
                revokeAndInvalidate(
                        session,
                        SessionRevokeReason.ALL_SESSIONS_REVOKED,
                        now
                );
                revoked++;
            }
        }
        return revoked;
    }

    private void requireUsableCurrent(
            List<AuthenticationSession> owned,
            UUID accountId,
            UUID currentSessionId,
            Instant now
    ) {
        AuthenticationSession current = findSession(
                owned,
                Objects.requireNonNull(currentSessionId)
        );
        if (current == null || !current.canAuthorize(accountId, policy.bffClientId(), now)) {
            throw SessionFailure.sessionUnavailable();
        }
    }

    private void revokeAndInvalidate(
            AuthenticationSession session,
            SessionRevokeReason reason,
            Instant now
    ) {
        AuthenticationSession revoked = session.revoke(reason, now);
        if (!revoked.equals(session)) {
            sessions.recordRevocation(revoked);
        }
        authorizations.invalidateAllForSession(
                session.id(),
                OAuthAuthorizationInvalidationReason.fromSessionReason(revoked.revokeReason()),
                now
        );
    }

    private static AuthenticationSession findSession(
            List<AuthenticationSession> sessions,
            UUID sessionId
    ) {
        return sessions.stream()
                .filter(session -> session.id().equals(sessionId))
                .findFirst()
                .orElse(null);
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
