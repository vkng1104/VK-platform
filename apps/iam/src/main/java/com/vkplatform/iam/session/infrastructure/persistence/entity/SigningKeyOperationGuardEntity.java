package com.vkplatform.iam.session.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "signing_key_operation_guard", schema = "iam_session")
public class SigningKeyOperationGuardEntity {
    @Id
    private short id;

    protected SigningKeyOperationGuardEntity() {
    }
}
