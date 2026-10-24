package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataAuthenticationSessionRepository extends JpaRepository<SessionEntity, UUID> {
}
