package com.vkplatform.iam.session.application.port.out;

import com.vkplatform.iam.session.domain.AuthenticationSession;

public interface AuthenticationSessionRepository {
    AuthenticationSession create(AuthenticationSession session);
}
