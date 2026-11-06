package com.vkplatform.iam.session.api;

public interface OAuthClientReadiness {
    Readiness readiness();

    record Readiness(boolean ready, String reason) {
    }
}
