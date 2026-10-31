package com.vkplatform.iam.session.api.command;

public record OperatorContext(String actorId, String operationId) {
    public OperatorContext {
        actorId = require(actorId, "operator actor");
        operationId = require(operationId, "operator operation ID");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value.strip();
    }
}
