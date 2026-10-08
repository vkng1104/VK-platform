package com.vkplatform.iam.platform.web;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record ApiError(
        String code,
        String message,
        @JsonProperty("request_id") String requestId,
        boolean retryable,
        Map<String, List<String>> fields
) {
}
