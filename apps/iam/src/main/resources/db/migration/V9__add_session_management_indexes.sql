CREATE INDEX sessions_account_status_created_id_idx
    ON iam_session.sessions (account_id, status, created_at DESC, id DESC)
    INCLUDE (expires_at);
