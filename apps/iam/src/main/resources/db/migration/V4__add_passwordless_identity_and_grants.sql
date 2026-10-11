ALTER TABLE iam_identity.email_verification_challenges
    DROP CONSTRAINT email_verification_challenges_purpose_check;

ALTER TABLE iam_identity.email_verification_challenges
    ADD CONSTRAINT email_verification_challenges_purpose_check
        CHECK (purpose IN ('restricted_resource_access', 'sign_in'));

CREATE TABLE iam_identity.accounts (
    id UUID PRIMARY KEY,
    status TEXT NOT NULL,
    token_not_before TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT accounts_status_check
        CHECK (status IN ('active', 'suspended', 'recovery_locked', 'deleted')),
    CONSTRAINT accounts_version_check CHECK (version >= 0),
    CONSTRAINT accounts_timeline_check CHECK (
        token_not_before >= created_at
        AND updated_at >= created_at
        AND (deleted_at IS NULL OR deleted_at >= created_at)
    ),
    CONSTRAINT accounts_deleted_state_check CHECK (
        (status = 'deleted' AND deleted_at IS NOT NULL)
        OR (status <> 'deleted' AND deleted_at IS NULL)
    )
);

CREATE TABLE iam_identity.login_identities (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES iam_identity.accounts (id) ON DELETE RESTRICT,
    kind TEXT NOT NULL,
    identifier_fingerprint BYTEA NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT login_identities_kind_check CHECK (kind IN ('email')),
    CONSTRAINT login_identities_fingerprint_check
        CHECK (OCTET_LENGTH(identifier_fingerprint) = 32),
    CONSTRAINT login_identities_timeline_check CHECK (verified_at >= created_at),
    CONSTRAINT login_identities_kind_fingerprint_unique
        UNIQUE (kind, identifier_fingerprint)
);

CREATE INDEX login_identities_account_idx
    ON iam_identity.login_identities (account_id);

CREATE TABLE iam_identity.verification_grants (
    id UUID PRIMARY KEY,
    purpose TEXT NOT NULL,
    account_id UUID NOT NULL REFERENCES iam_identity.accounts (id) ON DELETE RESTRICT,
    identity_fingerprint BYTEA NOT NULL,
    grant_digest BYTEA NOT NULL,
    audience VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT verification_grants_purpose_check CHECK (purpose IN ('sign_in')),
    CONSTRAINT verification_grants_identity_fingerprint_check
        CHECK (OCTET_LENGTH(identity_fingerprint) = 32),
    CONSTRAINT verification_grants_digest_check CHECK (OCTET_LENGTH(grant_digest) = 32),
    CONSTRAINT verification_grants_digest_unique UNIQUE (grant_digest),
    CONSTRAINT verification_grants_audience_check
        CHECK (audience <> '' AND audience = BTRIM(audience)),
    CONSTRAINT verification_grants_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT verification_grants_consumed_timeline_check
        CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX verification_grants_account_created_idx
    ON iam_identity.verification_grants (account_id, created_at DESC);

CREATE INDEX verification_grants_terminal_idx
    ON iam_identity.verification_grants (expires_at, consumed_at);

CREATE TABLE iam_identity.security_events (
    id UUID PRIMARY KEY,
    event_type TEXT NOT NULL,
    account_id UUID REFERENCES iam_identity.accounts (id) ON DELETE RESTRICT,
    target_id UUID,
    actor_id VARCHAR(128),
    request_id VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT security_events_type_check CHECK (
        event_type IN (
            'account_created',
            'login_identity_linked',
            'sign_in_grant_issued',
            'sign_in_grant_consumed',
            'account_suspended',
            'account_reactivated',
            'account_deleted'
        )
    ),
    CONSTRAINT security_events_actor_check
        CHECK (actor_id IS NULL OR (actor_id <> '' AND actor_id = BTRIM(actor_id))),
    CONSTRAINT security_events_request_check
        CHECK (request_id IS NULL OR (request_id <> '' AND request_id = BTRIM(request_id)))
);

CREATE INDEX security_events_account_occurred_idx
    ON iam_identity.security_events (account_id, occurred_at DESC);

CREATE INDEX security_events_occurred_idx
    ON iam_identity.security_events (occurred_at DESC);

CREATE TABLE iam_identity.identity_operation_guards (
    id SMALLINT PRIMARY KEY,
    CONSTRAINT identity_operation_guards_range_check CHECK (id BETWEEN 0 AND 63)
);

INSERT INTO iam_identity.identity_operation_guards (id)
SELECT value
FROM GENERATE_SERIES(0, 63) AS value;
