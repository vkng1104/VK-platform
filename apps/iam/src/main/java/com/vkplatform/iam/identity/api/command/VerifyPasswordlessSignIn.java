package com.vkplatform.iam.identity.api.command;

public record VerifyPasswordlessSignIn(
        String challengeId,
        String code,
        String requestId
) {
}
