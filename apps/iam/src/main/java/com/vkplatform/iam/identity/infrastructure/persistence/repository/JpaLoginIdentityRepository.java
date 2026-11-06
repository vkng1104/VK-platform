package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.application.port.out.LoginIdentityRepository;
import com.vkplatform.iam.identity.domain.IdentityKind;
import com.vkplatform.iam.identity.domain.LoginIdentity;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.LoginIdentityEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class JpaLoginIdentityRepository implements LoginIdentityRepository {
    private final SpringDataLoginIdentityRepository identities;

    JpaLoginIdentityRepository(SpringDataLoginIdentityRepository identities) {
        this.identities = identities;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LoginIdentity> findByKindAndFingerprint(IdentityKind kind, byte[] identifierFingerprint) {
        return identities.findByKindAndIdentifierFingerprint(kind, identifierFingerprint)
                .map(LoginIdentityEntity::toDomain);
    }

    @Override
    @Transactional
    public void create(LoginIdentity identity, byte[] identifierFingerprint) {
        identities.save(new LoginIdentityEntity(identity, identifierFingerprint));
    }
}
