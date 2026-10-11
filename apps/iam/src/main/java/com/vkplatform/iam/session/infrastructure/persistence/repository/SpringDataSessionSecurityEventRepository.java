package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionSecurityEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataSessionSecurityEventRepository extends JpaRepository<SessionSecurityEventEntity, UUID> {
}
