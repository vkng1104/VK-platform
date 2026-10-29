package com.vkplatform.iam.session.infrastructure.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "oauth_client_operation_guard", schema = "iam_session")
public class OAuthClientOperationGuardEntity {
    @Id
    private short id;

    protected OAuthClientOperationGuardEntity() {
    }
}
