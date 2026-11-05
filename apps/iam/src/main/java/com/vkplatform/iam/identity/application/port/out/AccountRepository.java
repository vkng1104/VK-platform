package com.vkplatform.iam.identity.application.port.out;

import com.vkplatform.iam.identity.domain.Account;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {
    void create(Account account);

    Optional<Account> findById(UUID id);

    /** Locks the account row until the caller's transaction ends. */
    Optional<Account> findLockedById(UUID id);

    /** Stores a status transition the caller applied. The account row is locked first. */
    void recordStatusChange(Account changed);
}
