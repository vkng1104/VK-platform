package com.vkplatform.iam.verification.application.port.out;

public interface EmailVerificationOperationGuardRepository {
    /**
     * Locks the single operation guard row until the caller's transaction ends. Operations that
     * must not overlap, such as rate-limited starts and cleanup batches, take this lock first.
     * Must run inside the caller's transaction.
     */
    void lock();
}
