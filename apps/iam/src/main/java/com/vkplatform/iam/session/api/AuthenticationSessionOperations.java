package com.vkplatform.iam.session.api;

import com.vkplatform.iam.session.api.command.ExchangeAuthenticationGrant;
import com.vkplatform.iam.session.api.result.EstablishedAuthenticationSession;

public interface AuthenticationSessionOperations {
    EstablishedAuthenticationSession exchange(ExchangeAuthenticationGrant command);
}
