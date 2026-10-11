package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.domain.IdentityKind;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.LoginIdentityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataLoginIdentityRepository extends JpaRepository<LoginIdentityEntity, UUID> {
    Optional<LoginIdentityEntity> findByKindAndIdentifierFingerprint(
            IdentityKind kind,
            byte[] identifierFingerprint
    );
}
