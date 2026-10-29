package com.vkplatform.iam.session.web.dto;

import java.util.List;

public record SessionsEnvelope(
        List<SessionResponse> sessions,
        SessionPageMetadata page
) {
    public SessionsEnvelope {
        sessions = List.copyOf(sessions);
    }
}
