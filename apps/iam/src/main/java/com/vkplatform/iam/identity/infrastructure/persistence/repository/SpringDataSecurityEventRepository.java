package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.infrastructure.persistence.entity.SecurityEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataSecurityEventRepository extends JpaRepository<SecurityEventEntity, UUID> {
}
