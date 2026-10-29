package com.vkplatform.iam.session.api;

import com.vkplatform.iam.session.api.command.RotateOAuthClientSecret;
import com.vkplatform.iam.session.api.result.OAuthClientSecretRotation;

public interface OAuthClientOperations {
    OAuthClientSecretRotation rotateSecret(RotateOAuthClientSecret command);
}
