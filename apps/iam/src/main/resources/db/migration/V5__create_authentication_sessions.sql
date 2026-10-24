CREATE SCHEMA iam_session;

CREATE TABLE iam_session.sessions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES iam_identity.accounts (id) ON DELETE RESTRICT,
    client_id VARCHAR(128) NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT sessions_client_check
        CHECK (client_id <> '' AND client_id = BTRIM(client_id)),
    CONSTRAINT sessions_status_check CHECK (status IN ('active')),
    CONSTRAINT sessions_expiry_check CHECK (expires_at > created_at)
);
