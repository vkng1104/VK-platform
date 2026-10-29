ALTER TABLE iam_session.sessions
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_refreshed_at TIMESTAMPTZ,
    ADD COLUMN compromised_at TIMESTAMPTZ;

UPDATE iam_session.sessions
SET last_refreshed_at = created_at;

ALTER TABLE iam_session.sessions
    ALTER COLUMN last_refreshed_at SET NOT NULL,
    DROP CONSTRAINT sessions_status_check,
    DROP CONSTRAINT sessions_revoke_reason_check,
    DROP CONSTRAINT sessions_revocation_timeline_check,
    DROP CONSTRAINT sessions_revocation_state_check,
    ADD CONSTRAINT sessions_status_check
        CHECK (status IN ('active', 'revoked', 'compromised')),
    ADD CONSTRAINT sessions_version_check CHECK (version >= 0),
    ADD CONSTRAINT sessions_revoke_reason_check CHECK (
        revoke_reason IS NULL OR revoke_reason IN (
            'user_request',
            'logout',
            'all_sessions_revoked',
            'account_state_changed',
            'refresh_token_reused'
        )
    ),
    ADD CONSTRAINT sessions_lifecycle_timeline_check CHECK (
        last_refreshed_at >= created_at
        AND (revoked_at IS NULL OR revoked_at >= last_refreshed_at)
        AND (compromised_at IS NULL OR compromised_at >= revoked_at)
    ),
    ADD CONSTRAINT sessions_lifecycle_state_check CHECK (
        (status = 'active'
            AND revoked_at IS NULL
            AND compromised_at IS NULL
            AND revoke_reason IS NULL)
        OR (status = 'revoked'
            AND revoked_at IS NOT NULL
            AND compromised_at IS NULL
            AND revoke_reason IS NOT NULL)
        OR (status = 'compromised'
            AND revoked_at IS NOT NULL
            AND compromised_at IS NOT NULL
            AND revoke_reason = 'refresh_token_reused')
    );

ALTER TABLE iam_session.oauth_authorizations
    ADD COLUMN access_token_digest BYTEA,
    ADD COLUMN access_token_issued_at TIMESTAMPTZ,
    ADD COLUMN access_token_expires_at TIMESTAMPTZ,
    ADD COLUMN access_token_metadata_json TEXT,
    ADD COLUMN access_token_type VARCHAR(100),
    ADD COLUMN access_token_scopes VARCHAR(1000),
    ADD COLUMN refresh_token_digest BYTEA,
    ADD COLUMN refresh_token_issued_at TIMESTAMPTZ,
    ADD COLUMN refresh_token_expires_at TIMESTAMPTZ,
    ADD COLUMN refresh_token_metadata_json TEXT,
    ADD COLUMN invalidated_at TIMESTAMPTZ,
    ADD COLUMN invalidation_reason VARCHAR(100),
    ADD CONSTRAINT oauth_authorizations_access_digest_check
        CHECK (access_token_digest IS NULL OR OCTET_LENGTH(access_token_digest) = 32),
    ADD CONSTRAINT oauth_authorizations_refresh_digest_check
        CHECK (refresh_token_digest IS NULL OR OCTET_LENGTH(refresh_token_digest) = 32),
    ADD CONSTRAINT oauth_authorizations_access_tuple_check CHECK (
        (access_token_digest IS NULL
            AND access_token_issued_at IS NULL
            AND access_token_expires_at IS NULL
            AND access_token_metadata_json IS NULL
            AND access_token_type IS NULL
            AND access_token_scopes IS NULL)
        OR (access_token_digest IS NOT NULL
            AND access_token_issued_at IS NOT NULL
            AND access_token_expires_at > access_token_issued_at
            AND access_token_metadata_json IS NOT NULL
            AND access_token_type IS NOT NULL)
    ),
    ADD CONSTRAINT oauth_authorizations_refresh_tuple_check CHECK (
        (refresh_token_digest IS NULL
            AND refresh_token_issued_at IS NULL
            AND refresh_token_expires_at IS NULL
            AND refresh_token_metadata_json IS NULL)
        OR (refresh_token_digest IS NOT NULL
            AND refresh_token_issued_at IS NOT NULL
            AND refresh_token_expires_at > refresh_token_issued_at
            AND refresh_token_metadata_json IS NOT NULL)
    ),
    ADD CONSTRAINT oauth_authorizations_invalidation_check CHECK (
        (invalidated_at IS NULL AND invalidation_reason IS NULL)
        OR (invalidated_at IS NOT NULL AND invalidation_reason IS NOT NULL)
    ),
    ADD CONSTRAINT oauth_authorizations_invalidation_reason_check CHECK (
        invalidation_reason IS NULL OR invalidation_reason IN (
            'authorization_removed',
            'logout',
            'user_request',
            'all_sessions_revoked',
            'account_state_changed',
            'refresh_token_reused',
            'expired'
        )
    );

CREATE UNIQUE INDEX oauth_authorizations_access_digest_idx
    ON iam_session.oauth_authorizations (access_token_digest)
    WHERE access_token_digest IS NOT NULL;

CREATE UNIQUE INDEX oauth_authorizations_refresh_digest_idx
    ON iam_session.oauth_authorizations (refresh_token_digest)
    WHERE refresh_token_digest IS NOT NULL;

CREATE INDEX oauth_authorizations_session_active_idx
    ON iam_session.oauth_authorizations (session_id)
    WHERE invalidated_at IS NULL;

CREATE TABLE iam_session.oauth_refresh_tokens (
    id UUID PRIMARY KEY,
    authorization_id VARCHAR(100) NOT NULL
        REFERENCES iam_session.oauth_authorizations (id) ON DELETE RESTRICT,
    family_id UUID NOT NULL,
    parent_id UUID REFERENCES iam_session.oauth_refresh_tokens (id) ON DELETE RESTRICT,
    replacement_id UUID,
    token_digest BYTEA NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    reuse_detected_at TIMESTAMPTZ,
    CONSTRAINT oauth_refresh_tokens_digest_check CHECK (OCTET_LENGTH(token_digest) = 32),
    CONSTRAINT oauth_refresh_tokens_digest_unique UNIQUE (token_digest),
    CONSTRAINT oauth_refresh_tokens_expiry_check CHECK (expires_at > issued_at),
    CONSTRAINT oauth_refresh_tokens_consumed_timeline_check
        CHECK (consumed_at IS NULL OR consumed_at >= issued_at),
    CONSTRAINT oauth_refresh_tokens_reuse_timeline_check CHECK (
        reuse_detected_at IS NULL
        OR (consumed_at IS NOT NULL AND reuse_detected_at >= consumed_at)
    ),
    CONSTRAINT oauth_refresh_tokens_replacement_state_check CHECK (
        (consumed_at IS NULL AND replacement_id IS NULL)
        OR (consumed_at IS NOT NULL AND replacement_id IS NOT NULL)
    ),
    CONSTRAINT oauth_refresh_tokens_replacement_identity_check
        CHECK (replacement_id IS NULL OR replacement_id <> id),
    CONSTRAINT oauth_refresh_tokens_parent_identity_check
        CHECK (parent_id IS NULL OR parent_id <> id)
);

ALTER TABLE iam_session.oauth_refresh_tokens
    ADD CONSTRAINT oauth_refresh_tokens_replacement_fk
        FOREIGN KEY (replacement_id)
        REFERENCES iam_session.oauth_refresh_tokens (id)
        ON DELETE RESTRICT;

CREATE INDEX oauth_refresh_tokens_authorization_issued_idx
    ON iam_session.oauth_refresh_tokens (authorization_id, issued_at DESC);

CREATE INDEX oauth_refresh_tokens_family_issued_idx
    ON iam_session.oauth_refresh_tokens (family_id, issued_at DESC);
