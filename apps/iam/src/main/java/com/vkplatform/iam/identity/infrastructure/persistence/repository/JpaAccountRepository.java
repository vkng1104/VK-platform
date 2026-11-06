package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.application.port.out.AccountRepository;
import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.IdentityFailure;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.AccountEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaAccountRepository implements AccountRepository {
    private final SpringDataAccountRepository accounts;

    JpaAccountRepository(SpringDataAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional
    public void create(Account account) {
        accounts.save(new AccountEntity(account));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> findById(UUID id) {
        return accounts.findById(id).map(AccountEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<Account> findLockedById(UUID id) {
        return accounts.findLockedById(id).map(AccountEntity::toDomain);
    }

    @Override
    @Transactional
    public void recordStatusChange(Account changed) {
        accounts.findLockedById(changed.id())
                .orElseThrow(IdentityFailure::accountNotFound)
                .apply(changed);
    }
}
