package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.SigningKeyEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataSigningKeyEventRepository extends JpaRepository<SigningKeyEventEntity, UUID> {
}
