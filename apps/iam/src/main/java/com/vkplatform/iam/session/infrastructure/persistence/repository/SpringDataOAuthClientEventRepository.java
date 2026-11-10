package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OAuthClientEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataOAuthClientEventRepository
        extends JpaRepository<OAuthClientEventEntity, UUID> {
}
