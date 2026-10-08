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
                .contains("EMAIL_VERIFICATION_RATE_LIMITED")
                .contains("INVALID_OR_EXPIRED_CODE")
                .contains("service: iam")
                .doesNotContain("/api/v1/projects")
                .doesNotContain("service: authn");
    }
}
