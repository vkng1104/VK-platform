package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.application.port.out.VerificationGrantRepository;
import com.vkplatform.iam.identity.domain.VerificationGrant;
import com.vkplatform.iam.identity.infrastructure.persistence.entity.VerificationGrantEntity;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class JpaVerificationGrantRepository implements VerificationGrantRepository {
    private final SpringDataVerificationGrantRepository grants;

    JpaVerificationGrantRepository(SpringDataVerificationGrantRepository grants) {
        this.grants = grants;
    }

    @Override
    @Transactional
    public void create(VerificationGrant grant, byte[] identityFingerprint, byte[] grantDigest) {
        grants.save(new VerificationGrantEntity(grant, identityFingerprint, grantDigest));
    }

    @Override
    @Transactional
    public Optional<VerificationGrant> findLockedByDigest(byte[] grantDigest) {
        return grants.findByGrantDigest(grantDigest).map(VerificationGrantEntity::toDomain);
    }

    @Override
    @Transactional
    public void recordConsumption(VerificationGrant consumed) {
        grants.findLockedById(consumed.id())
                .orElseThrow(() -> new IllegalStateException("verification grant does not exist"))
                .apply(consumed);
    }
}
