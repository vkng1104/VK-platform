package com.vkplatform.iam.session.api;

import com.vkplatform.iam.session.api.command.OperatorContext;

public interface OAuthClientRegistrationOperations {
    int reconcile(OperatorContext context);
}
