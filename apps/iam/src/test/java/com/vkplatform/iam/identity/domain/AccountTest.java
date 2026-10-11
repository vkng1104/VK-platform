package com.vkplatform.iam.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {
    private static final Instant CREATED = Instant.parse("2026-10-11T01:00:00Z");

    @Test
    void transitionsAccountStateAndAdvancesTokenBoundary() {
        Account active = Account.create(UUID.randomUUID(), CREATED);
        Account suspended = active.suspend(CREATED.plusSeconds(10));
        Account reactivated = suspended.reactivate(CREATED.plusSeconds(20));
        Account deleted = reactivated.delete(CREATED.plusSeconds(30));

        assertThat(suspended.status()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(suspended.tokenNotBefore()).isEqualTo(CREATED.plusSeconds(10));
        assertThat(reactivated.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(reactivated.tokenNotBefore()).isEqualTo(CREATED.plusSeconds(20));
        assertThat(deleted.status()).isEqualTo(AccountStatus.DELETED);
        assertThat(deleted.deletedAt()).isEqualTo(CREATED.plusSeconds(30));
        assertThat(deleted.tokenNotBefore()).isEqualTo(CREATED.plusSeconds(30));
    }

    @Test
    void idempotentTransitionsDoNotChangeTimestamps() {
        Account active = Account.create(UUID.randomUUID(), CREATED);
        Account suspended = active.suspend(CREATED.plusSeconds(10));
        Account deleted = suspended.delete(CREATED.plusSeconds(20));

        assertThat(active.reactivate(CREATED.plusSeconds(1))).isSameAs(active);
        assertThat(suspended.suspend(CREATED.plusSeconds(11))).isSameAs(suspended);
        assertThat(deleted.delete(CREATED.plusSeconds(21))).isSameAs(deleted);
    }

    @Test
    void deletedAccountsCannotBecomeActiveOrAuthenticate() {
        Account deleted = Account.create(UUID.randomUUID(), CREATED)
                .delete(CREATED.plusSeconds(1));

        assertThatThrownBy(() -> deleted.reactivate(CREATED.plusSeconds(2)))
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_UNAVAILABLE));
        assertThatThrownBy(deleted::requireActive)
                .isInstanceOfSatisfying(IdentityFailure.class, failure ->
                        assertThat(failure.reason()).isEqualTo(IdentityFailure.Reason.ACCOUNT_UNAVAILABLE));
    }

    @Test
    void rejectsBackwardTimestampsAndInvalidDeletedState() {
        Account active = Account.create(UUID.randomUUID(), CREATED);

        assertThatThrownBy(() -> active.suspend(CREATED.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Account(
                UUID.randomUUID(),
                AccountStatus.DELETED,
                CREATED,
                0,
                CREATED,
                CREATED,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
