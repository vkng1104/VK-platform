package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.domain.AuthenticationSession;

import java.util.Optional;
import java.util.UUID;

public interface AuthenticationSessionRepository {
    AuthenticationSession create(AuthenticationSession session);

    Optional<AuthenticationSession> findById(UUID id);
}
