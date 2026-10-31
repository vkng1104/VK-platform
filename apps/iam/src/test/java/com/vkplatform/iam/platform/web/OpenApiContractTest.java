package com.vkplatform.iam.platform.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {
    @Test
    void contractDocumentsOnlyIamOwnedRoutesAndStableErrorCodes() throws Exception {
        String contract = new ClassPathResource("openapi/openapi.yaml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(contract)
                .contains("title: VK Platform IAM API")
                .contains("/healthz:")
                .contains("/api/v1/email-verifications:")
                .contains("/api/v1/email-verifications/{id}/verify:")
                .contains("/api/v1/authentication/challenges:")
                .contains("/api/v1/authentication/challenges/{id}/verify:")
                .contains("/api/v1/authentication/grants/exchange:")
                .contains("/api/v1/authentication/session:")
                .contains("/api/v1/authentication/sessions:")
                .contains("/api/v1/authentication/sessions/{session_id}:")
                .contains("/api/v1/authentication/logout:")
                .contains("operationId: getCurrentAuthenticationSession")
                .contains("operationId: listAuthenticationSessions")
                .contains("operationId: revokeAuthenticationSession")
                .contains("operationId: revokeAllAuthenticationSessions")
                .contains("operationId: logoutAuthenticationSession")
                .contains("AuthenticationSessionsResponse:")
                .contains("bearerAuth:")
                .contains("/oauth2/jwks:")
                .contains("/oauth2/authorize:")
                .contains("/oauth2/token:")
                .contains("operationId: exchangeOAuthToken")
                .contains("- client_credentials")
                .contains("- refresh_token")
                .contains("refresh_token:")
                .contains("OAuthAccessTokenResponse:")
                .contains("JsonWebKeySet:")
                .contains("bffClientAuth:")
                .contains("oauthClientAuth:")
                .contains("BffAuthenticationRequired:")
                .contains("AUTHENTICATION_FAILED")
                .contains("EMAIL_VERIFICATION_RATE_LIMITED")
                .contains("INVALID_OR_EXPIRED_CODE")
                .contains("service: iam")
                .doesNotContain("/api/v1/projects")
                .doesNotContain("service: authn");
    }
}
