package com.vkplatform.iam.session.infrastructure.config;

import org.springframework.http.HttpMethod;

enum BffSessionOperation {
    EXCHANGE_AUTHENTICATION_GRANT(
            HttpMethod.POST,
            "/api/v1/authentication/grants/exchange"
    );

    private final HttpMethod method;
    private final String path;

    BffSessionOperation(HttpMethod method, String path) {
        this.method = method;
        this.path = path;
    }

    String method() {
        return method.name();
    }

    String path() {
        return path;
    }
}
