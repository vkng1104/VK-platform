package com.vkplatform.iam.session.api.result;

import java.util.UUID;

public record EstablishedSession(UUID accountId, SessionSummary session) {
}
