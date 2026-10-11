package com.vkplatform.iam.identity.api.command;

public record StartPasswordlessSignIn(
        String email,
        String requesterAddress
) {
}
