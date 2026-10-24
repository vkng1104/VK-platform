CREATE TABLE iam_session.oauth_authorizations (
    id VARCHAR(100) PRIMARY KEY,
    registered_client_id VARCHAR(128) NOT NULL,
    principal_name VARCHAR(200) NOT NULL,
    session_id UUID NOT NULL REFERENCES iam_session.sessions (id) ON DELETE RESTRICT,
    authorization_grant_type VARCHAR(64) NOT NULL,
    authorized_scopes VARCHAR(1000) NOT NULL,
    attributes_json TEXT NOT NULL,
    state_digest BYTEA,
    authorization_code_digest BYTEA,
    authorization_code_issued_at TIMESTAMPTZ,
    authorization_code_expires_at TIMESTAMPTZ,
    authorization_code_metadata_json TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT oauth_authorizations_client_check CHECK (
        registered_client_id <> '' AND registered_client_id = BTRIM(registered_client_id)
    ),
    CONSTRAINT oauth_authorizations_principal_check CHECK (
        principal_name <> '' AND principal_name = BTRIM(principal_name)
    ),
    CONSTRAINT oauth_authorizations_grant_check CHECK (
        authorization_grant_type = 'authorization_code'
    ),
    CONSTRAINT oauth_authorizations_state_digest_check CHECK (
        state_digest IS NULL OR OCTET_LENGTH(state_digest) = 32
    ),
    CONSTRAINT oauth_authorizations_code_digest_check CHECK (
        authorization_code_digest IS NULL OR OCTET_LENGTH(authorization_code_digest) = 32
    ),
    CONSTRAINT oauth_authorizations_code_tuple_check CHECK (
        (authorization_code_digest IS NULL
            AND authorization_code_issued_at IS NULL
            AND authorization_code_expires_at IS NULL
            AND authorization_code_metadata_json IS NULL)
        OR (authorization_code_digest IS NOT NULL
            AND authorization_code_issued_at IS NOT NULL
            AND authorization_code_expires_at > authorization_code_issued_at
            AND authorization_code_metadata_json IS NOT NULL)
    ),
    CONSTRAINT oauth_authorizations_version_check CHECK (version >= 0)
);

CREATE UNIQUE INDEX oauth_authorizations_state_digest_idx
    ON iam_session.oauth_authorizations (state_digest)
    WHERE state_digest IS NOT NULL;

CREATE UNIQUE INDEX oauth_authorizations_code_digest_idx
    ON iam_session.oauth_authorizations (authorization_code_digest)
    WHERE authorization_code_digest IS NOT NULL;

CREATE INDEX oauth_authorizations_session_idx
    ON iam_session.oauth_authorizations (session_id);
