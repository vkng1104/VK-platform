package com.vkplatform.iam.session.web.dto;

public record ExchangeAuthenticationGrantRequest(
        String grant,
        String audience
) {
}
