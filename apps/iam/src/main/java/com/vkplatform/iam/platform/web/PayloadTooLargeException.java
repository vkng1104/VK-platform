package com.vkplatform.iam.platform.web;

import java.io.IOException;

public final class PayloadTooLargeException extends IOException {
    public PayloadTooLargeException() {
        super("request body exceeds the configured endpoint limit");
    }
}
