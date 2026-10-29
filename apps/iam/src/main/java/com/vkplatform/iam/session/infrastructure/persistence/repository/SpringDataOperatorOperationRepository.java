package com.vkplatform.iam.session.infrastructure.persistence.repository;

import com.vkplatform.iam.session.infrastructure.persistence.entity.OperatorOperationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOperatorOperationRepository
        extends JpaRepository<OperatorOperationEntity, String> {
}
