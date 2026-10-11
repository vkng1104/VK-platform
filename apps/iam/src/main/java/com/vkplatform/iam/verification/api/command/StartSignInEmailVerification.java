package com.vkplatform.iam.verification.api.command;

public record StartSignInEmailVerification(
        String email,
        String requesterAddress
) {
}
