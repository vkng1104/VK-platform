package com.vkplatform.iam.identity.application.port.out;

public interface IdentityOperationGuardRepository {
    /**
     * Locks the guard row that serializes work on one login identifier until the caller's
     * transaction ends. Two sign-ins for the same identifier always take the same guard.
     */
    void lockForIdentifier(byte[] identifierFingerprint);
}
