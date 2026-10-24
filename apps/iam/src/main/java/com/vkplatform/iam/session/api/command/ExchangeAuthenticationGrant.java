package com.vkplatform.iam.session.api.command;

public record ExchangeAuthenticationGrant(
        String grant,
        String audience,
        String requestId
) {
}
