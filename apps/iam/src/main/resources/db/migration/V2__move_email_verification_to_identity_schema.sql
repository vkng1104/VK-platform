CREATE SCHEMA IF NOT EXISTS iam_identity;

ALTER TABLE public.email_verification_challenges
    SET SCHEMA iam_identity;

CREATE INDEX email_verification_challenges_verified_idx
    ON iam_identity.email_verification_challenges (verified_at)
    WHERE verified_at IS NOT NULL;

CREATE INDEX email_verification_challenges_invalidated_idx
    ON iam_identity.email_verification_challenges (invalidated_at)
    WHERE invalidated_at IS NOT NULL;

CREATE INDEX email_verification_challenges_failed_created_idx
    ON iam_identity.email_verification_challenges (created_at)
    WHERE delivery_status = 'failed';
