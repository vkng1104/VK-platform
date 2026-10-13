package com.vkplatform.iam.session.api;

import com.vkplatform.iam.session.api.command.ExchangeSignInGrant;
import com.vkplatform.iam.session.api.command.RevokeAllSessions;
import com.vkplatform.iam.session.api.command.RevokeSession;
import com.vkplatform.iam.session.api.result.SessionSummary;
import com.vkplatform.iam.session.api.result.EstablishedSession;

import java.util.List;
import java.util.UUID;

public interface SessionOperations {
    EstablishedSession exchange(ExchangeSignInGrant command);

    SessionSummary session(UUID accountId, UUID sessionId);

    List<SessionSummary> sessions(UUID accountId);

    void revoke(RevokeSession command);

    void logout(RevokeSession command);

    int revokeAll(RevokeAllSessions command);
}
