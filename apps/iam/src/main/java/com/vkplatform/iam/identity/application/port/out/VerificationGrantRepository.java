package com.vkplatform.iam.identity.application.port.out;

import com.vkplatform.iam.identity.domain.VerificationGrant;

import java.util.Optional;

public interface VerificationGrantRepository {
    /** Stores only the digest of the grant value and the fingerprint of the verified identity. */
    void create(VerificationGrant grant, byte[] identityFingerprint, byte[] grantDigest);

    /** Locks the grant row until the caller's transaction ends. */
    Optional<VerificationGrant> findLockedByDigest(byte[] grantDigest);

    /** Stores a consumption the caller applied. The grant row is locked first. */
    void recordConsumption(VerificationGrant consumed);
}
