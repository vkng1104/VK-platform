package com.vkplatform.iam.session.infrastructure.event;

import com.vkplatform.iam.identity.api.AccountAuthenticationInvalidated;
import com.vkplatform.iam.session.application.service.AccountAuthenticationInvalidationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AccountAuthenticationInvalidatedListener {
    private final AccountAuthenticationInvalidationService invalidations;

    public AccountAuthenticationInvalidatedListener(
            AccountAuthenticationInvalidationService invalidations
    ) {
        this.invalidations = invalidations;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    void invalidate(AccountAuthenticationInvalidated event) {
        invalidations.invalidateAccount(event.accountId(), event.occurredAt());
    }
}
