package com.vkplatform.iam.session.api.result;

import com.vkplatform.iam.session.domain.AuthenticationSession;

import java.util.UUID;

public record EstablishedAuthenticationSession(
        UUID accountId,
        AuthenticationSession session
) {
}
