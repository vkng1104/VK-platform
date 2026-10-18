CREATE SCHEMA iam_session;

CREATE TABLE iam_session.sessions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES iam_identity.accounts (id) ON DELETE RESTRICT,
    client_id VARCHAR(128) NOT NULL,
    status TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    last_refreshed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    compromised_at TIMESTAMPTZ,
    revoke_reason TEXT,
    CONSTRAINT sessions_client_check
        CHECK (client_id <> '' AND client_id = BTRIM(client_id)),
    CONSTRAINT sessions_status_check
        CHECK (status IN ('active', 'revoked', 'compromised')),
    CONSTRAINT sessions_version_check CHECK (version >= 0),
    CONSTRAINT sessions_revoke_reason_check CHECK (
        revoke_reason IS NULL OR revoke_reason IN (
            'logout',
            'user_request',
            'all_sessions_revoked',
            'account_state_changed',
            'refresh_token_reused',
            'expired'
        )
    ),
    CONSTRAINT sessions_timeline_check CHECK (
        last_refreshed_at >= created_at
        AND expires_at > created_at
        AND (revoked_at IS NULL OR revoked_at >= created_at)
        AND (compromised_at IS NULL OR compromised_at >= created_at)
    ),
    CONSTRAINT sessions_terminal_state_check CHECK (
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
    )
);

CREATE INDEX sessions_account_status_created_idx
    ON iam_session.sessions (account_id, status, created_at DESC);

CREATE INDEX sessions_terminal_expiry_idx
    ON iam_session.sessions (status, expires_at);

CREATE TABLE iam_session.signing_key_operation_guard (
    id SMALLINT PRIMARY KEY,
    CONSTRAINT signing_key_operation_guard_id_check CHECK (id = 1)
);

INSERT INTO iam_session.signing_key_operation_guard (id) VALUES (1);

CREATE TABLE iam_session.signing_keys (
    id VARCHAR(128) PRIMARY KEY,
    algorithm VARCHAR(16) NOT NULL,
    public_key_x509 BYTEA NOT NULL,
    encrypted_private_key BYTEA NOT NULL,
    encryption_nonce BYTEA NOT NULL,
    protection_key_version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL,
    publish_until TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT signing_keys_algorithm_check CHECK (algorithm = 'RS256'),
    CONSTRAINT signing_keys_public_key_check CHECK (OCTET_LENGTH(public_key_x509) >= 256),
    CONSTRAINT signing_keys_private_key_check CHECK (OCTET_LENGTH(encrypted_private_key) >= 256),
    CONSTRAINT signing_keys_nonce_check CHECK (OCTET_LENGTH(encryption_nonce) = 12),
    CONSTRAINT signing_keys_protection_version_check CHECK (
        protection_key_version <> '' AND protection_key_version = BTRIM(protection_key_version)
    ),
    CONSTRAINT signing_keys_status_check
        CHECK (status IN ('ACTIVE', 'RETIRING', 'RETIRED', 'COMPROMISED')),
    CONSTRAINT signing_keys_version_check CHECK (version >= 0),
    CONSTRAINT signing_keys_timeline_check CHECK (
        activated_at >= created_at
        AND (publish_until IS NULL OR publish_until >= activated_at)
        AND (retired_at IS NULL OR retired_at >= activated_at)
    ),
    CONSTRAINT signing_keys_state_check CHECK (
        (status = 'ACTIVE' AND publish_until IS NULL AND retired_at IS NULL)
        OR (status = 'RETIRING' AND publish_until IS NOT NULL AND retired_at IS NULL)
        OR (status = 'RETIRED' AND publish_until IS NOT NULL AND retired_at IS NOT NULL)
        OR (status = 'COMPROMISED' AND publish_until IS NOT NULL AND retired_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX signing_keys_one_active_idx
    ON iam_session.signing_keys (status)
    WHERE status = 'ACTIVE';

CREATE INDEX signing_keys_published_idx
    ON iam_session.signing_keys (status, publish_until);

CREATE TABLE iam_session.signing_key_events (
    id UUID PRIMARY KEY,
    key_id VARCHAR(128) NOT NULL REFERENCES iam_session.signing_keys (id) ON DELETE RESTRICT,
    event_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT signing_key_events_type_check
        CHECK (event_type IN ('bootstrapped', 'activated', 'retired', 'compromised', 'reencrypted')),
    CONSTRAINT signing_key_events_context_check CHECK (
        actor_id <> '' AND actor_id = BTRIM(actor_id)
        AND operation_id <> '' AND operation_id = BTRIM(operation_id)
    )
);

CREATE TABLE iam_session.oauth_client_operation_guard (
    id SMALLINT PRIMARY KEY,
    CONSTRAINT oauth_client_operation_guard_id_check CHECK (id = 1)
);

INSERT INTO iam_session.oauth_client_operation_guard (id) VALUES (1);

CREATE TABLE iam_session.oauth_clients (
    id VARCHAR(100) PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL,
    client_id_issued_at TIMESTAMPTZ NOT NULL,
    client_secret VARCHAR(200),
    client_secret_expires_at TIMESTAMPTZ,
    client_name VARCHAR(200) NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types VARCHAR(1000) NOT NULL,
    redirect_uris VARCHAR(2000),
    post_logout_redirect_uris VARCHAR(2000),
    scopes VARCHAR(1000) NOT NULL,
    client_settings TEXT NOT NULL,
    token_settings TEXT NOT NULL,
    CONSTRAINT oauth_clients_client_id_unique UNIQUE (client_id),
    CONSTRAINT oauth_clients_client_id_check
        CHECK (client_id <> '' AND client_id = BTRIM(client_id)),
    CONSTRAINT oauth_clients_name_check
        CHECK (client_name <> '' AND client_name = BTRIM(client_name))
);

CREATE TABLE iam_session.oauth_client_events (
    id UUID PRIMARY KEY,
    registered_client_id VARCHAR(100) NOT NULL
        REFERENCES iam_session.oauth_clients (id) ON DELETE RESTRICT,
    event_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT oauth_client_events_type_check
        CHECK (event_type IN ('registered', 'reconciled', 'credential_rotated')),
    CONSTRAINT oauth_client_events_context_check CHECK (
        actor_id <> '' AND actor_id = BTRIM(actor_id)
        AND operation_id <> '' AND operation_id = BTRIM(operation_id)
    )
);

CREATE TABLE iam_session.oauth_authorizations (
    id VARCHAR(100) PRIMARY KEY,
    registered_client_id VARCHAR(100) NOT NULL
        REFERENCES iam_session.oauth_clients (id) ON DELETE RESTRICT,
    principal_name VARCHAR(200) NOT NULL,
    session_id UUID REFERENCES iam_session.sessions (id) ON DELETE RESTRICT,
    authorization_grant_type VARCHAR(100) NOT NULL,
    authorized_scopes VARCHAR(1000),
    attributes TEXT NOT NULL,
    state_digest BYTEA,
    authorization_code_digest BYTEA,
    authorization_code_issued_at TIMESTAMPTZ,
    authorization_code_expires_at TIMESTAMPTZ,
    authorization_code_metadata TEXT,
    access_token_digest BYTEA,
    access_token_issued_at TIMESTAMPTZ,
    access_token_expires_at TIMESTAMPTZ,
    access_token_metadata TEXT,
    access_token_type VARCHAR(100),
    access_token_scopes VARCHAR(1000),
    refresh_token_digest BYTEA,
    refresh_token_issued_at TIMESTAMPTZ,
    refresh_token_expires_at TIMESTAMPTZ,
    refresh_token_metadata TEXT,
    invalidated_at TIMESTAMPTZ,
    invalidation_reason VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT oauth_authorizations_state_digest_check
        CHECK (state_digest IS NULL OR OCTET_LENGTH(state_digest) = 32),
    CONSTRAINT oauth_authorizations_code_digest_check
        CHECK (authorization_code_digest IS NULL OR OCTET_LENGTH(authorization_code_digest) = 32),
    CONSTRAINT oauth_authorizations_access_digest_check
        CHECK (access_token_digest IS NULL OR OCTET_LENGTH(access_token_digest) = 32),
    CONSTRAINT oauth_authorizations_refresh_digest_check
        CHECK (refresh_token_digest IS NULL OR OCTET_LENGTH(refresh_token_digest) = 32),
    CONSTRAINT oauth_authorizations_code_tuple_check CHECK (
        (authorization_code_digest IS NULL
            AND authorization_code_issued_at IS NULL
            AND authorization_code_expires_at IS NULL)
        OR (authorization_code_digest IS NOT NULL
            AND authorization_code_issued_at IS NOT NULL
            AND authorization_code_expires_at > authorization_code_issued_at)
    ),
    CONSTRAINT oauth_authorizations_access_tuple_check CHECK (
        (access_token_digest IS NULL
            AND access_token_issued_at IS NULL
            AND access_token_expires_at IS NULL
            AND access_token_type IS NULL)
        OR (access_token_digest IS NOT NULL
            AND access_token_issued_at IS NOT NULL
            AND access_token_expires_at > access_token_issued_at
            AND access_token_type IS NOT NULL)
    ),
    CONSTRAINT oauth_authorizations_refresh_tuple_check CHECK (
        (refresh_token_digest IS NULL
            AND refresh_token_issued_at IS NULL
            AND refresh_token_expires_at IS NULL)
        OR (refresh_token_digest IS NOT NULL
            AND refresh_token_issued_at IS NOT NULL
            AND refresh_token_expires_at > refresh_token_issued_at)
    ),
    CONSTRAINT oauth_authorizations_version_check CHECK (version >= 0),
    CONSTRAINT oauth_authorizations_invalidation_check CHECK (
        (invalidated_at IS NULL AND invalidation_reason IS NULL)
        OR (invalidated_at IS NOT NULL AND invalidation_reason IS NOT NULL)
    ),
    CONSTRAINT oauth_authorizations_invalidation_reason_check CHECK (
        invalidation_reason IS NULL OR invalidation_reason IN (
            'authorization_removed',
            'logout',
            'user_request',
            'all_sessions_revoked',
            'account_state_changed',
            'refresh_token_reused',
            'expired'
        )
    )
);

CREATE INDEX oauth_authorizations_session_active_idx
    ON iam_session.oauth_authorizations (session_id)
    WHERE invalidated_at IS NULL;

CREATE UNIQUE INDEX oauth_authorizations_state_digest_idx
    ON iam_session.oauth_authorizations (state_digest)
    WHERE state_digest IS NOT NULL;

CREATE UNIQUE INDEX oauth_authorizations_code_digest_idx
    ON iam_session.oauth_authorizations (authorization_code_digest)
    WHERE authorization_code_digest IS NOT NULL;

CREATE UNIQUE INDEX oauth_authorizations_access_digest_idx
    ON iam_session.oauth_authorizations (access_token_digest)
    WHERE access_token_digest IS NOT NULL;

CREATE INDEX oauth_authorizations_expiry_idx
    ON iam_session.oauth_authorizations (refresh_token_expires_at, access_token_expires_at);

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

ALTER TABLE iam_identity.security_events
    DROP CONSTRAINT security_events_type_check;

ALTER TABLE iam_identity.security_events
    ADD CONSTRAINT security_events_type_check CHECK (
        event_type IN (
            'account_created',
            'login_identity_linked',
            'sign_in_grant_issued',
            'sign_in_grant_consumed',
            'account_suspended',
            'account_reactivated',
            'account_deleted',
            'session_created',
            'refresh_token_rotated',
            'refresh_token_reused',
            'session_revoked',
            'all_sessions_revoked'
        )
    );
