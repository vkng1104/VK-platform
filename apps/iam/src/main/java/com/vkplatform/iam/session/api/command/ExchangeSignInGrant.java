package com.vkplatform.iam.session.api.command;

public record ExchangeSignInGrant(
        String grant,
        String audience,
        String requestId
) {
}
