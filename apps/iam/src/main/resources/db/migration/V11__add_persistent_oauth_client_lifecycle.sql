CREATE TABLE iam_session.oauth_client_operation_guard (
    id SMALLINT PRIMARY KEY,
    CONSTRAINT oauth_client_operation_guard_id_check CHECK (id = 1)
);

INSERT INTO iam_session.oauth_client_operation_guard (id) VALUES (1);

CREATE TABLE iam_session.oauth_clients (
    id VARCHAR(100) PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL,
    client_id_issued_at TIMESTAMPTZ NOT NULL,
    client_secret VARCHAR(512),
    client_secret_expires_at TIMESTAMPTZ,
    client_name VARCHAR(200) NOT NULL,
    client_authentication_methods VARCHAR(1000) NOT NULL,
    authorization_grant_types VARCHAR(1000) NOT NULL,
    redirect_uris VARCHAR(2000),
    post_logout_redirect_uris VARCHAR(2000),
    scopes VARCHAR(1000) NOT NULL,
    client_settings TEXT NOT NULL,
    token_settings TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT oauth_clients_client_id_unique UNIQUE (client_id),
    CONSTRAINT oauth_clients_id_check CHECK (id <> '' AND id = BTRIM(id)),
    CONSTRAINT oauth_clients_client_id_check CHECK (
        client_id <> '' AND client_id = BTRIM(client_id)
    ),
    CONSTRAINT oauth_clients_name_check CHECK (
        client_name <> '' AND client_name = BTRIM(client_name)
    ),
    CONSTRAINT oauth_clients_methods_check CHECK (client_authentication_methods <> ''),
    CONSTRAINT oauth_clients_grants_check CHECK (authorization_grant_types <> ''),
    CONSTRAINT oauth_clients_scopes_check CHECK (scopes <> ''),
    CONSTRAINT oauth_clients_version_check CHECK (version >= 0),
    CONSTRAINT oauth_clients_secret_expiry_check CHECK (
        client_secret_expires_at IS NULL OR client_secret IS NOT NULL
    )
);

CREATE INDEX oauth_clients_secret_expiry_idx
    ON iam_session.oauth_clients (client_secret_expires_at)
    WHERE client_secret_expires_at IS NOT NULL;

CREATE TABLE iam_session.oauth_client_events (
    id UUID PRIMARY KEY,
    registered_client_id VARCHAR(100) NOT NULL
        REFERENCES iam_session.oauth_clients (id) ON DELETE RESTRICT,
    event_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT oauth_client_events_type_check CHECK (
        event_type IN ('registered', 'reconciled', 'credential_rotated')
    ),
    CONSTRAINT oauth_client_events_context_check CHECK (
        actor_id <> '' AND actor_id = BTRIM(actor_id)
        AND operation_id <> '' AND operation_id = BTRIM(operation_id)
    ),
    CONSTRAINT oauth_client_events_operation_event_unique
        UNIQUE (operation_id, registered_client_id, event_type)
);

CREATE INDEX oauth_client_events_client_time_idx
    ON iam_session.oauth_client_events (registered_client_id, occurred_at DESC);

ALTER TABLE iam_session.operator_operations
    ADD COLUMN request_fingerprint BYTEA,
    ADD COLUMN result_timestamp TIMESTAMPTZ,
    ADD CONSTRAINT operator_operations_request_fingerprint_check CHECK (
        request_fingerprint IS NULL OR OCTET_LENGTH(request_fingerprint) = 32
    );

ALTER TABLE iam_session.oauth_authorizations
    ALTER COLUMN session_id DROP NOT NULL,
    DROP CONSTRAINT oauth_authorizations_grant_check,
    ADD CONSTRAINT oauth_authorizations_grant_check CHECK (
        authorization_grant_type IN ('authorization_code', 'client_credentials')
    ),
    ADD CONSTRAINT oauth_authorizations_registered_client_fk
    FOREIGN KEY (registered_client_id)
    REFERENCES iam_session.oauth_clients (id)
    ON DELETE RESTRICT
    NOT VALID;
