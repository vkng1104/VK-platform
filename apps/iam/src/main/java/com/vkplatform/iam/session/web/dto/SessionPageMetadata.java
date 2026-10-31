package com.vkplatform.iam.session.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SessionPageMetadata(
        int number,
        int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") int totalPages
) {
}
