ALTER TABLE iam_session.sessions
    ADD COLUMN revoked_at TIMESTAMPTZ,
    ADD COLUMN revoke_reason TEXT,
    DROP CONSTRAINT sessions_status_check,
    ADD CONSTRAINT sessions_status_check CHECK (status IN ('active', 'revoked')),
    ADD CONSTRAINT sessions_revoke_reason_check CHECK (
        revoke_reason IS NULL OR revoke_reason IN (
            'user_request',
            'logout',
            'all_sessions_revoked',
            'account_state_changed'
        )
    ),
    ADD CONSTRAINT sessions_revocation_timeline_check CHECK (
        revoked_at IS NULL OR revoked_at >= created_at
    ),
    ADD CONSTRAINT sessions_revocation_state_check CHECK (
        (status = 'active' AND revoked_at IS NULL AND revoke_reason IS NULL)
        OR (status = 'revoked' AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
    );

CREATE INDEX sessions_account_status_id_idx
    ON iam_session.sessions (account_id, status, id);
