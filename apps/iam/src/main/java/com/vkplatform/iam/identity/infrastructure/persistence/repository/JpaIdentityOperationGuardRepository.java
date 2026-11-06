package com.vkplatform.iam.identity.infrastructure.persistence.repository;

import com.vkplatform.iam.identity.application.port.out.IdentityOperationGuardRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaIdentityOperationGuardRepository implements IdentityOperationGuardRepository {
    // Must match the number of guard rows the migration seeds.
    private static final int GUARD_COUNT = 64;

    private final SpringDataIdentityOperationGuardRepository guards;

    JpaIdentityOperationGuardRepository(SpringDataIdentityOperationGuardRepository guards) {
        this.guards = guards;
    }

    @Override
    @Transactional
    public void lockForIdentifier(byte[] identifierFingerprint) {
        short guardId = (short) (Byte.toUnsignedInt(identifierFingerprint[0]) % GUARD_COUNT);
        guards.findById(guardId)
                .orElseThrow(() -> new IllegalStateException("identity operation guard is missing"));
    }
}
