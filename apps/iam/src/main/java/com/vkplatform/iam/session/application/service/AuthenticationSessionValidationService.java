package com.vkplatform.iam.session.application.service;

import com.vkplatform.iam.session.application.port.out.AuthenticationSessionRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Answers whether a browser's stored session can still authorize its account and client. */
public class AuthenticationSessionValidationService {
    private final AuthenticationSessionRepository sessions;
    private final Clock clock;

    public AuthenticationSessionValidationService(AuthenticationSessionRepository sessions, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Read-only; takes no lock. */
    @Transactional(readOnly = true)
    public boolean isUsable(UUID sessionId, UUID accountId, String clientId) {
        return sessions.findById(sessionId)
                .filter(session -> session.canAuthorize(accountId, clientId, clock.instant()))
                .isPresent();
    }
}
