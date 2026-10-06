CREATE TABLE email_verification_challenges (
    id UUID PRIMARY KEY,
    purpose TEXT NOT NULL,
    email_fingerprint BYTEA NOT NULL,
    requester_fingerprint BYTEA,
    otp_digest BYTEA NOT NULL,
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    max_attempts SMALLINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    resend_not_before TIMESTAMPTZ NOT NULL,
    delivery_status TEXT NOT NULL,
    sent_at TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT email_verification_challenges_purpose_check
        CHECK (purpose IN ('restricted_resource_access')),
    CONSTRAINT email_verification_challenges_email_fingerprint_check
        CHECK (OCTET_LENGTH(email_fingerprint) = 32),
    CONSTRAINT email_verification_challenges_requester_fingerprint_check
        CHECK (requester_fingerprint IS NULL OR OCTET_LENGTH(requester_fingerprint) = 32),
    CONSTRAINT email_verification_challenges_otp_digest_check
        CHECK (OCTET_LENGTH(otp_digest) = 32),
    CONSTRAINT email_verification_challenges_attempt_count_check
        CHECK (attempt_count >= 0 AND attempt_count <= max_attempts),
    CONSTRAINT email_verification_challenges_max_attempts_check
        CHECK (max_attempts > 0),
    CONSTRAINT email_verification_challenges_expiry_check
        CHECK (expires_at > created_at),
    CONSTRAINT email_verification_challenges_resend_check
        CHECK (resend_not_before > created_at),
    CONSTRAINT email_verification_challenges_delivery_status_check
        CHECK (delivery_status IN ('pending', 'sent', 'failed')),
    CONSTRAINT email_verification_challenges_sent_state_check
        CHECK (
            (delivery_status = 'pending' AND sent_at IS NULL)
            OR (delivery_status = 'sent' AND sent_at IS NOT NULL)
            OR delivery_status = 'failed'
        ),
    CONSTRAINT email_verification_challenges_verified_state_check
        CHECK (verified_at IS NULL OR (delivery_status = 'sent' AND sent_at IS NOT NULL)),
    CONSTRAINT email_verification_challenges_timeline_check
        CHECK (
            (sent_at IS NULL OR sent_at >= created_at)
            AND (verified_at IS NULL OR verified_at >= created_at)
            AND (invalidated_at IS NULL OR invalidated_at >= created_at)
        )
);

CREATE INDEX email_verification_challenges_email_created_idx
    ON email_verification_challenges (email_fingerprint, created_at DESC);

CREATE INDEX email_verification_challenges_requester_created_idx
    ON email_verification_challenges (requester_fingerprint, created_at DESC)
    WHERE requester_fingerprint IS NOT NULL;

CREATE INDEX email_verification_challenges_created_idx
    ON email_verification_challenges (created_at);

CREATE INDEX email_verification_challenges_expires_idx
    ON email_verification_challenges (expires_at);
