package com.vkplatform.iam.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Account(
        UUID id,
        AccountStatus status,
        Instant tokenNotBefore,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
    public Account {
        Objects.requireNonNull(id);
        Objects.requireNonNull(status);
        Objects.requireNonNull(tokenNotBefore);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        if (version < 0 || updatedAt.isBefore(createdAt) || tokenNotBefore.isBefore(createdAt)) {
            throw new IllegalArgumentException("account timeline is invalid");
        }
        if ((status == AccountStatus.DELETED) != (deletedAt != null)) {
            throw new IllegalArgumentException("deleted account state is invalid");
        }
    }

    public static Account create(UUID id, Instant now) {
        return new Account(id, AccountStatus.ACTIVE, now, 0, now, now, null);
    }

    public Account suspend(Instant now) {
        requireMutable(now);
        if (status == AccountStatus.SUSPENDED) {
            return this;
        }
        return transition(AccountStatus.SUSPENDED, now, null);
    }

    public Account reactivate(Instant now) {
        requireMutable(now);
        if (status == AccountStatus.ACTIVE) {
            return this;
        }
        return transition(AccountStatus.ACTIVE, now, null);
    }

    public Account delete(Instant now) {
        requireTimestamp(now);
        if (status == AccountStatus.DELETED) {
            return this;
        }
        return transition(AccountStatus.DELETED, now, now);
    }

    public void requireActive() {
        if (status != AccountStatus.ACTIVE) {
            throw IdentityFailure.accountUnavailable();
        }
    }

    private Account transition(AccountStatus next, Instant now, Instant nextDeletedAt) {
        Instant nextTokenNotBefore = tokenNotBefore.isAfter(now) ? tokenNotBefore : now;
        return new Account(id, next, nextTokenNotBefore, version, createdAt, now, nextDeletedAt);
    }

    private void requireMutable(Instant now) {
        requireTimestamp(now);
        if (status == AccountStatus.DELETED) {
            throw IdentityFailure.accountUnavailable();
        }
    }

    private void requireTimestamp(Instant now) {
        Objects.requireNonNull(now);
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException("account change precedes its current state");
        }
    }
}
