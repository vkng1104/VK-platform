package com.vkplatform.iam.session.api.result;

public record IamRetentionResult(
        int expiredSessions,
        int invalidatedAuthorizations,
        int deletedRefreshTokens,
        int deletedAuthorizations,
        int deletedSessions,
        int deletedAuditEvents,
        int deletedSigningKeys
) {
    public IamRetentionResult {
        if (expiredSessions < 0
                || invalidatedAuthorizations < 0
                || deletedRefreshTokens < 0
                || deletedAuthorizations < 0
                || deletedSessions < 0
                || deletedAuditEvents < 0
                || deletedSigningKeys < 0) {
            throw new IllegalArgumentException("retention result counts cannot be negative");
        }
    }

    public int totalChanges() {
        return expiredSessions
                + invalidatedAuthorizations
                + deletedRefreshTokens
                + deletedAuthorizations
                + deletedSessions
                + deletedAuditEvents
                + deletedSigningKeys;
    }

    public IamRetentionResult withDeletedAuditEvents(int count) {
        return new IamRetentionResult(
                expiredSessions,
                invalidatedAuthorizations,
                deletedRefreshTokens,
                deletedAuthorizations,
                deletedSessions,
                count,
                deletedSigningKeys
        );
    }

    public String storedValue() {
        return String.join(",",
                Integer.toString(expiredSessions),
                Integer.toString(invalidatedAuthorizations),
                Integer.toString(deletedRefreshTokens),
                Integer.toString(deletedAuthorizations),
                Integer.toString(deletedSessions),
                Integer.toString(deletedAuditEvents),
                Integer.toString(deletedSigningKeys)
        );
    }

    public static IamRetentionResult fromStoredValue(String value) {
        if (value == null) {
            throw new IllegalStateException("stored retention result is missing");
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 7) {
            throw new IllegalStateException("stored retention result is invalid");
        }
        try {
            return new IamRetentionResult(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]),
                    Integer.parseInt(parts[4]),
                    Integer.parseInt(parts[5]),
                    Integer.parseInt(parts[6])
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("stored retention result is invalid", exception);
        }
    }
}
