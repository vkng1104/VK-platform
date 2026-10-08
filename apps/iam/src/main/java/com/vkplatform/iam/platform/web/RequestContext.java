package com.vkplatform.iam.platform.web;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestContext {
    public static final String REQUEST_ID_ATTRIBUTE = RequestContext.class.getName() + ".requestId";

    private RequestContext() {
    }

    public static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }
}
