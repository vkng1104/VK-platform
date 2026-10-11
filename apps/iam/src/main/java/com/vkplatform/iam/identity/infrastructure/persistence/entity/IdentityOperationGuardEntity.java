package com.vkplatform.iam.identity.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "identity_operation_guards", schema = "iam_identity")
public class IdentityOperationGuardEntity {
    @Id
    private short id;

    protected IdentityOperationGuardEntity() {
    }
}
