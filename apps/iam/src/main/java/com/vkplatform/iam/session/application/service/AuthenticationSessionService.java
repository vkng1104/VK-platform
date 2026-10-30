package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.SecurityAuditEventType;
import com.vkplatform.iam.identity.api.SecurityAuditOperations;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.command.RecordSecurityAuditEvent;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.api.AuthenticationSessionOperations;
import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.api.command.RevokeAllAuthenticationSessions;
import com.vkplatform.iam.session.api.command.RevokeAuthenticationSession;
import com.vkplatform.iam.session.api.result.AuthenticationSessionPage;
import com.vkplatform.iam.session.api.result.AuthenticationSessionSummary;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.application.port.out.IamSecurityTelemetry;
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
    private final SecurityAuditOperations audit;
    private final IamSecurityTelemetry telemetry;
    private final SessionPolicy policy;
    private final Clock clock;

    public AuthenticationSessionService(
            PasswordlessIdentityOperations identities,
            AuthenticationSessionRepository sessions,
            SecurityAuditOperations audit,
            IamSecurityTelemetry telemetry,
            SessionPolicy policy,
            Clock clock
    ) {
        this.identities = Objects.requireNonNull(identities);
        this.sessions = Objects.requireNonNull(sessions);
        this.audit = Objects.requireNonNull(audit);
        this.telemetry = Objects.requireNonNull(telemetry);
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
        audit.record(new RecordSecurityAuditEvent(
                SecurityAuditEventType.SESSION_CREATED,
                account.accountId(),
                session.id(),
                policy.bffClientId(),
                command.requestId(),
                now
        ));
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
        long startedAt = telemetry.start();
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
            telemetry.record(
                    IamSecurityTelemetry.Operation.SESSION_REVOCATION,
                    IamSecurityTelemetry.Outcome.FAILURE,
                    startedAt
            );
            throw SessionFailure.sessionUnavailable();
        }
        if (result == AuthenticationSessionRepository.RevokeResult.TARGET_NOT_FOUND) {
            telemetry.record(
                    IamSecurityTelemetry.Operation.SESSION_REVOCATION,
                    IamSecurityTelemetry.Outcome.NOT_FOUND,
                    startedAt
            );
            throw SessionFailure.sessionNotFound();
        }
        if (result == AuthenticationSessionRepository.RevokeResult.REVOKED) {
            recordRevocation(
                    SecurityAuditEventType.SESSION_REVOKED,
                    command.accountId(),
                    command.targetSessionId(),
                    command.requestId()
            );
        }
        telemetry.recordAfterCommit(
                IamSecurityTelemetry.Operation.SESSION_REVOCATION,
                result == AuthenticationSessionRepository.RevokeResult.REVOKED
                        ? IamSecurityTelemetry.Outcome.SUCCESS
                        : IamSecurityTelemetry.Outcome.NOOP,
                startedAt
        );
    }

    @Override
    @Transactional
    public void logout(RevokeAuthenticationSession command) {
        Objects.requireNonNull(command);
        long startedAt = telemetry.start();
        requireContext(command.requestId());
        if (!Objects.equals(command.currentSessionId(), command.targetSessionId())) {
            throw SessionFailure.invalidRequest();
        }
        boolean changed = sessions.revokeOwnedAndInvalidate(
                Objects.requireNonNull(command.accountId()),
                Objects.requireNonNull(command.targetSessionId()),
                SessionRevokeReason.LOGOUT,
                clock.instant()
        );
        if (changed) {
            recordRevocation(
                    SecurityAuditEventType.SESSION_REVOKED,
                    command.accountId(),
                    command.targetSessionId(),
                    command.requestId()
            );
        }
        telemetry.recordAfterCommit(
                IamSecurityTelemetry.Operation.SESSION_REVOCATION,
                changed ? IamSecurityTelemetry.Outcome.SUCCESS : IamSecurityTelemetry.Outcome.NOOP,
                startedAt
        );
    }

    @Override
    @Transactional
    public int revokeAll(RevokeAllAuthenticationSessions command) {
        Objects.requireNonNull(command);
        long startedAt = telemetry.start();
        requireContext(command.requestId());
        AuthenticationSessionRepository.RevokeAllResult result = sessions.revokeAllFromCurrent(
                command.accountId(),
                command.currentSessionId(),
                policy.bffClientId(),
                SessionRevokeReason.ALL_SESSIONS_REVOKED,
                clock.instant()
        );
        if (!result.currentAvailable()) {
            telemetry.record(
                    IamSecurityTelemetry.Operation.SESSION_REVOCATION,
                    IamSecurityTelemetry.Outcome.FAILURE,
                    startedAt
            );
            throw SessionFailure.sessionUnavailable();
        }
        if (result.revokedCount() > 0) {
            recordRevocation(
                    SecurityAuditEventType.ALL_SESSIONS_REVOKED,
                    command.accountId(),
                    command.currentSessionId(),
                    command.requestId()
            );
        }
        telemetry.recordAfterCommit(
                IamSecurityTelemetry.Operation.SESSION_REVOCATION,
                result.revokedCount() > 0
                        ? IamSecurityTelemetry.Outcome.SUCCESS
                        : IamSecurityTelemetry.Outcome.NOOP,
                startedAt
        );
        return result.revokedCount();
    }

    private void recordRevocation(
            SecurityAuditEventType type,
            UUID accountId,
            UUID targetId,
            String requestId
    ) {
        audit.record(new RecordSecurityAuditEvent(
                type,
                accountId,
                targetId,
                accountId.toString(),
                requestId,
                clock.instant()
        ));
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
