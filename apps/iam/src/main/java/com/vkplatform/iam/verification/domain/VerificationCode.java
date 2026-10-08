package com.vkplatform.iam.verification.domain;

import java.util.regex.Pattern;

public record VerificationCode(String value) {
    private static final Pattern FORMAT = Pattern.compile("^[0-9]{6}$");

    public VerificationCode {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw VerificationFailure.invalidCodeFormat();
        }
    }
}
