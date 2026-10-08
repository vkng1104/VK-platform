package com.vkplatform.iam.verification.domain;

import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

public record EmailAddress(String value) {
    private static final int MAXIMUM_LENGTH = 254;
    private static final Pattern LOCAL_PART = Pattern.compile("^[^\\s@\\\"(),:;<>\\[\\]\\\\]+$");
    private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}$");

    public EmailAddress {
        value = normalize(value);
    }

    public String masked() {
        int separator = value.lastIndexOf('@');
        String local = value.substring(0, separator);
        if (local.codePointCount(0, local.length()) == 1) {
            return "*" + value.substring(separator);
        }
        int firstEnd = local.offsetByCodePoints(0, 1);
        int lastStart = local.offsetByCodePoints(0, local.codePointCount(0, local.length()) - 1);
        int hidden = local.codePointCount(firstEnd, lastStart);
        return local.substring(0, firstEnd) + "*".repeat(hidden) + local.substring(lastStart)
                + value.substring(separator);
    }

    private static String normalize(String candidate) {
        if (candidate == null) {
            throw VerificationFailure.invalidEmail();
        }
        String normalized = candidate.strip();
        if (normalized.isEmpty() || normalized.length() > MAXIMUM_LENGTH
                || normalized.contains("\r") || normalized.contains("\n")) {
            throw VerificationFailure.invalidEmail();
        }
        int separator = normalized.lastIndexOf('@');
        if (separator <= 0 || separator == normalized.length() - 1) {
            throw VerificationFailure.invalidEmail();
        }
        String local = normalized.substring(0, separator);
        String domain;
        try {
            domain = IDN.toASCII(normalized.substring(separator + 1)).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw VerificationFailure.invalidEmail();
        }
        if (!LOCAL_PART.matcher(local).matches() || !DOMAIN.matcher(domain).matches()) {
            throw VerificationFailure.invalidEmail();
        }
        return local + "@" + domain;
    }
}
