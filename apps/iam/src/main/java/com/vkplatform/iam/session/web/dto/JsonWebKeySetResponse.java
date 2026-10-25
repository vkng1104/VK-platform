package com.vkplatform.iam.session.web.dto;

import java.util.List;

public record JsonWebKeySetResponse(List<JsonWebKeyResponse> keys) {
    public JsonWebKeySetResponse {
        keys = List.copyOf(keys);
    }
}
