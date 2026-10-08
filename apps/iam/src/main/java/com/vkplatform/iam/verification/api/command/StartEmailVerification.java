package com.vkplatform.iam.verification.api.command;

public record StartEmailVerification(
        String email,
        String purpose,
        String requesterAddress
) {
}
