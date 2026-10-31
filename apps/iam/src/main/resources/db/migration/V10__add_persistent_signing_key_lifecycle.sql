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
    compromised_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT signing_keys_id_check CHECK (id <> '' AND id = BTRIM(id)),
    CONSTRAINT signing_keys_algorithm_check CHECK (algorithm = 'RS256'),
    CONSTRAINT signing_keys_public_key_check CHECK (OCTET_LENGTH(public_key_x509) >= 256),
    CONSTRAINT signing_keys_private_key_check CHECK (OCTET_LENGTH(encrypted_private_key) >= 256),
    CONSTRAINT signing_keys_nonce_check CHECK (OCTET_LENGTH(encryption_nonce) = 12),
    CONSTRAINT signing_keys_protection_version_check CHECK (
        protection_key_version <> '' AND protection_key_version = BTRIM(protection_key_version)
    ),
    CONSTRAINT signing_keys_status_check CHECK (
        status IN ('ACTIVE', 'RETIRING', 'RETIRED', 'COMPROMISED')
    ),
    CONSTRAINT signing_keys_version_check CHECK (version >= 0),
    CONSTRAINT signing_keys_timeline_check CHECK (
        activated_at >= created_at
        AND (publish_until IS NULL OR publish_until >= activated_at)
        AND (retired_at IS NULL OR retired_at >= publish_until)
        AND (compromised_at IS NULL OR compromised_at >= activated_at)
    ),
    CONSTRAINT signing_keys_state_check CHECK (
        (status = 'ACTIVE'
            AND publish_until IS NULL
            AND retired_at IS NULL
            AND compromised_at IS NULL)
        OR (status = 'RETIRING'
            AND publish_until IS NOT NULL
            AND retired_at IS NULL
            AND compromised_at IS NULL)
        OR (status = 'RETIRED'
            AND publish_until IS NOT NULL
            AND retired_at IS NOT NULL
            AND compromised_at IS NULL)
        OR (status = 'COMPROMISED'
            AND publish_until IS NOT NULL
            AND retired_at IS NULL
            AND compromised_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX signing_keys_one_active_idx
    ON iam_session.signing_keys (status)
    WHERE status = 'ACTIVE';

CREATE INDEX signing_keys_publication_idx
    ON iam_session.signing_keys (status, publish_until, activated_at DESC);

CREATE INDEX signing_keys_protection_version_id_idx
    ON iam_session.signing_keys (protection_key_version, id);

CREATE TABLE iam_session.signing_key_events (
    id UUID PRIMARY KEY,
    key_id VARCHAR(128) NOT NULL
        REFERENCES iam_session.signing_keys (id) ON DELETE RESTRICT,
    event_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT signing_key_events_type_check CHECK (
        event_type IN (
            'bootstrapped',
            'rotation_started',
            'activated',
            'retired',
            'compromised',
            'reencrypted'
        )
    ),
    CONSTRAINT signing_key_events_context_check CHECK (
        actor_id <> '' AND actor_id = BTRIM(actor_id)
        AND operation_id <> '' AND operation_id = BTRIM(operation_id)
    ),
    CONSTRAINT signing_key_events_operation_event_unique
        UNIQUE (operation_id, key_id, event_type)
);

CREATE INDEX signing_key_events_key_time_idx
    ON iam_session.signing_key_events (key_id, occurred_at DESC);

CREATE TABLE iam_session.operator_operations (
    operation_id VARCHAR(128) PRIMARY KEY,
    operation_type VARCHAR(64) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    target_id VARCHAR(128),
    result_key_id VARCHAR(128),
    result_count INTEGER,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT operator_operations_context_check CHECK (
        operation_id <> '' AND operation_id = BTRIM(operation_id)
        AND operation_type <> '' AND operation_type = BTRIM(operation_type)
        AND actor_id <> '' AND actor_id = BTRIM(actor_id)
        AND (target_id IS NULL OR (target_id <> '' AND target_id = BTRIM(target_id)))
        AND (result_key_id IS NULL OR (result_key_id <> '' AND result_key_id = BTRIM(result_key_id)))
    ),
    CONSTRAINT operator_operations_result_count_check CHECK (
        result_count IS NULL OR result_count >= 0
    )
);

CREATE INDEX operator_operations_type_completed_idx
    ON iam_session.operator_operations (operation_type, completed_at DESC);
