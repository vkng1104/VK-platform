package com.vkplatform.iam.verification.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_verification_operation_guards", schema = "iam_identity")
public class EmailVerificationOperationGuardEntity {
    @Id
    private short id;

    protected EmailVerificationOperationGuardEntity() {
    }
}
