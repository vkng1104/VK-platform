package com.vkplatform.iam.identity.application.port.out;

import com.vkplatform.iam.identity.domain.IdentityKind;
import com.vkplatform.iam.identity.domain.LoginIdentity;

import java.util.Optional;

public interface LoginIdentityRepository {
    Optional<LoginIdentity> findByKindAndFingerprint(IdentityKind kind, byte[] identifierFingerprint);

    void create(LoginIdentity identity, byte[] identifierFingerprint);
}
