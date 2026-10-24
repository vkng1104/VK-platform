package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.identity.api.PasswordlessIdentityOperations;
import com.vkplatform.iam.identity.api.command.ConsumeSignInGrant;
import com.vkplatform.iam.identity.api.result.AuthenticatedAccount;
import com.vkplatform.iam.session.api.AuthenticationSessionOperations;
import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;
import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import com.vkplatform.iam.session.domain.AuthenticationSession;
import com.vkplatform.iam.session.domain.SessionPolicy;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class AuthenticationSessionService implements AuthenticationSessionOperations {
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
}
