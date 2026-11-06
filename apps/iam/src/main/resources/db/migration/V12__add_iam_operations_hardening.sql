ALTER TABLE iam_session.sessions
    DROP CONSTRAINT sessions_revoke_reason_check,
    ADD CONSTRAINT sessions_revoke_reason_check CHECK (
        revoke_reason IS NULL OR revoke_reason IN (
            'user_request',
            'logout',
            'all_sessions_revoked',
            'account_state_changed',
            'refresh_token_reused',
            'expired'
        )
    );

ALTER TABLE iam_identity.security_events
    DROP CONSTRAINT security_events_type_check,
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

ALTER TABLE iam_session.operator_operations
    ADD COLUMN result_details TEXT,
    ADD CONSTRAINT operator_operations_result_details_check CHECK (
        result_details IS NULL OR result_details <> ''
    );

CREATE INDEX sessions_active_expiry_cleanup_idx
    ON iam_session.sessions (expires_at, id)
    WHERE status = 'active';

CREATE INDEX sessions_terminal_cleanup_idx
    ON iam_session.sessions (COALESCE(compromised_at, revoked_at), id)
    WHERE status <> 'active';

CREATE INDEX oauth_authorizations_invalidation_cleanup_idx
    ON iam_session.oauth_authorizations (invalidated_at, id)
    WHERE invalidated_at IS NOT NULL;

CREATE INDEX oauth_authorizations_sessionless_expiry_idx
    ON iam_session.oauth_authorizations (access_token_expires_at, id)
    WHERE session_id IS NULL AND invalidated_at IS NULL;

CREATE INDEX security_events_cleanup_idx
    ON iam_identity.security_events (occurred_at, id);

CREATE INDEX signing_key_events_cleanup_idx
    ON iam_session.signing_key_events (occurred_at, id);

CREATE INDEX oauth_client_events_cleanup_idx
    ON iam_session.oauth_client_events (occurred_at, id);

CREATE INDEX signing_keys_terminal_cleanup_idx
    ON iam_session.signing_keys (COALESCE(retired_at, compromised_at), id)
    WHERE status IN ('RETIRED', 'COMPROMISED');
