package com.vkplatform.iam.identity.infrastructure.persistence.entity;

import com.vkplatform.iam.identity.domain.Account;
import com.vkplatform.iam.identity.domain.AccountStatus;
import com.vkplatform.iam.identity.infrastructure.persistence.converter.AccountStatusConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts", schema = "iam_identity")
public class AccountEntity extends AbstractUuidEntity {
    @Id
    private UUID id;

    @Convert(converter = AccountStatusConverter.class)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "token_not_before", nullable = false)
    private Instant tokenNotBefore;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected AccountEntity() {
    }

    public AccountEntity(Account account) {
        this.id = account.id();
        apply(account);
        this.version = account.version();
        this.createdAt = account.createdAt();
    }

    public Account toDomain() {
        return new Account(id, status, tokenNotBefore, version, createdAt, updatedAt, deletedAt);
    }

    public void apply(Account account) {
        if (id != null && !id.equals(account.id())) {
            throw new IllegalArgumentException("account identity cannot change");
        }
        this.id = account.id();
        this.status = account.status();
        this.tokenNotBefore = account.tokenNotBefore();
        this.updatedAt = account.updatedAt();
        this.deletedAt = account.deletedAt();
    }

    public UUID id() {
        return id;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
