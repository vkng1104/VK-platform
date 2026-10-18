package com.vkplatform.iam.session.infrastructure.event;

import com.vkplatform.iam.identity.api.event.AccountAuthenticationInvalidated;
import com.vkplatform.iam.session.application.port.out.SessionRepository;
import com.vkplatform.iam.session.domain.SessionRevokeReason;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AccountAuthenticationInvalidatedListener {
    private final SessionRepository sessions;

    public AccountAuthenticationInvalidatedListener(SessionRepository sessions) {
        this.sessions = sessions;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void invalidate(AccountAuthenticationInvalidated event) {
        sessions.revokeAll(
                event.accountId(),
                null,
                event.actorId(),
                SessionRevokeReason.ACCOUNT_STATE_CHANGED,
                event.occurredAt(),
                event.requestId()
        );
    }
}
