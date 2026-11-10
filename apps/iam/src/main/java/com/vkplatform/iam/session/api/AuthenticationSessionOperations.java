package com.vkplatform.iam.session.api;

import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.api.command.RevokeAllAuthenticationSessions;
import com.vkplatform.iam.session.api.command.RevokeAuthenticationSession;
import com.vkplatform.iam.session.api.result.AuthenticationSessionPage;
import com.vkplatform.iam.session.api.result.AuthenticationSessionSummary;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;

import java.util.UUID;

public interface AuthenticationSessionOperations {
    EstablishedAuthenticationSession exchange(ExchangeAuthenticationGrant command);

    AuthenticationSessionSummary current(UUID accountId, UUID sessionId);

    AuthenticationSessionPage sessions(UUID accountId, UUID currentSessionId, int page, int size);

    void revoke(RevokeAuthenticationSession command);

    void logout(RevokeAuthenticationSession command);

    int revokeAll(RevokeAllAuthenticationSessions command);
}
