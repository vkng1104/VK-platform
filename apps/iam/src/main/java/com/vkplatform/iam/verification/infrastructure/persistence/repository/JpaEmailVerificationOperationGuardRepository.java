package com.vkplatform.iam.verification.infrastructure.persistence.repository;

import com.vkplatform.iam.verification.application.port.out.EmailVerificationOperationGuardRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaEmailVerificationOperationGuardRepository implements EmailVerificationOperationGuardRepository {
    private static final short OPERATION_GUARD_ID = 1;

    private final SpringDataEmailVerificationOperationGuardRepository operationGuards;

    JpaEmailVerificationOperationGuardRepository(SpringDataEmailVerificationOperationGuardRepository operationGuards) {
        this.operationGuards = operationGuards;
    }

    /** A lock taken outside a transaction would be released at once, so a caller transaction is required. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        operationGuards.findById(OPERATION_GUARD_ID)
                .orElseThrow(() -> new IllegalStateException("email verification operation guard is missing"));
    }
}
