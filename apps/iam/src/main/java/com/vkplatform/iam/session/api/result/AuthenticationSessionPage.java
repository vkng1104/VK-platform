package com.vkplatform.iam.session.api.result;

import java.util.List;

public record AuthenticationSessionPage(
        List<AuthenticationSessionSummary> sessions,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public AuthenticationSessionPage {
        sessions = List.copyOf(sessions);
    }
}
