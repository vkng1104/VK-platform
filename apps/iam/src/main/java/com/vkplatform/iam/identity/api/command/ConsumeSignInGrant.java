package com.vkplatform.iam.identity.api.command;

public record ConsumeSignInGrant(
        String grant,
        String audience,
        String requestId
) {
}
