package emailverification

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	repositoryQueryTimeout = 5 * time.Second
	startAdvisoryLockID    = int64(836_472_901)
)

type PostgreSQLRepository struct {
	pool *pgxpool.Pool
}

func NewPostgreSQLRepository(pool *pgxpool.Pool) (*PostgreSQLRepository, error) {
	if pool == nil {
		return nil, ErrMissingPool
	}

	return &PostgreSQLRepository{pool: pool}, nil
}

func (repository *PostgreSQLRepository) CreatePending(
	ctx context.Context,
	challenge Challenge,
	policy StartPolicy,
) error {
	ctx, cancel := context.WithTimeout(ctx, repositoryQueryTimeout)
	defer cancel()

	transaction, err := repository.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("begin challenge creation: %w", err)
	}
	defer func() {
		_ = transaction.Rollback(context.WithoutCancel(ctx))
	}()

	if _, err := transaction.Exec(ctx, `SELECT pg_advisory_xact_lock($1)`, startAdvisoryLockID); err != nil {
		return fmt.Errorf("lock challenge creation: %w", err)
	}

	retryAt, err := challengeRetryAt(ctx, transaction, challenge, policy)
	if err != nil {
		return err
	}
	if retryAt.After(challenge.CreatedAt) {
		return &RateLimitError{RetryAt: retryAt}
	}

	if _, err := transaction.Exec(ctx, `
		UPDATE email_verification_challenges
		SET invalidated_at = $1
		WHERE email_fingerprint = $2
			AND purpose = $3
			AND verified_at IS NULL
			AND invalidated_at IS NULL
	`, challenge.CreatedAt, challenge.EmailFingerprint, challenge.Purpose); err != nil {
		return fmt.Errorf("invalidate earlier email challenges: %w", err)
	}

	if _, err := transaction.Exec(ctx, `
		INSERT INTO email_verification_challenges (
			id,
			purpose,
			email_fingerprint,
			requester_fingerprint,
			otp_digest,
			attempt_count,
			max_attempts,
			expires_at,
			resend_not_before,
			delivery_status,
			created_at
		)
		VALUES ($1, $2, $3, $4, $5, 0, $6, $7, $8, $9, $10)
	`,
		challenge.ID,
		challenge.Purpose,
		challenge.EmailFingerprint,
		nullableBytes(challenge.RequesterFingerprint),
		challenge.OTPDigest,
		challenge.MaxAttempts,
		challenge.ExpiresAt,
		challenge.ResendNotBefore,
		challenge.DeliveryStatus,
		challenge.CreatedAt,
	); err != nil {
		return fmt.Errorf("insert email verification challenge: %w", err)
	}

	if err := transaction.Commit(ctx); err != nil {
		return fmt.Errorf("commit challenge creation: %w", err)
	}

	return nil
}

func (repository *PostgreSQLRepository) MarkSent(
	ctx context.Context,
	id string,
	sentAt time.Time,
) error {
	ctx, cancel := context.WithTimeout(ctx, repositoryQueryTimeout)
	defer cancel()

	commandTag, err := repository.pool.Exec(ctx, `
		UPDATE email_verification_challenges
		SET delivery_status = 'sent', sent_at = $2
		WHERE id = $1
			AND delivery_status = 'pending'
			AND invalidated_at IS NULL
	`, id, sentAt)
	if err != nil {
		return fmt.Errorf("update challenge delivery success: %w", err)
	}
	if commandTag.RowsAffected() != 1 {
		return ErrUnexpectedChallengeState
	}

	return nil
}

func (repository *PostgreSQLRepository) MarkFailed(ctx context.Context, id string) error {
	ctx, cancel := context.WithTimeout(ctx, repositoryQueryTimeout)
	defer cancel()

	commandTag, err := repository.pool.Exec(ctx, `
		UPDATE email_verification_challenges
		SET delivery_status = 'failed'
		WHERE id = $1 AND delivery_status = 'pending'
	`, id)
	if err != nil {
		return fmt.Errorf("update challenge delivery failure: %w", err)
	}
	if commandTag.RowsAffected() != 1 {
		return ErrUnexpectedChallengeState
	}

	return nil
}

func (repository *PostgreSQLRepository) DeleteExpired(
	ctx context.Context,
	before time.Time,
) (int64, error) {
	ctx, cancel := context.WithTimeout(ctx, repositoryQueryTimeout)
	defer cancel()

	commandTag, err := repository.pool.Exec(ctx, `
		DELETE FROM email_verification_challenges
		WHERE expires_at < $1
	`, before)
	if err != nil {
		return 0, fmt.Errorf("delete expired email verification challenges: %w", err)
	}

	return commandTag.RowsAffected(), nil
}

func (repository *PostgreSQLRepository) BeginVerification(
	ctx context.Context,
) (VerificationTransaction, error) {
	transaction, err := repository.pool.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("begin verification transaction: %w", err)
	}

	return &postgresVerificationTransaction{transaction: transaction}, nil
}

type postgresVerificationTransaction struct {
	transaction pgx.Tx
}

func (transaction *postgresVerificationTransaction) FindChallengeForUpdate(
	ctx context.Context,
	id string,
) (Challenge, error) {
	row := transaction.transaction.QueryRow(ctx, `
		SELECT
			id,
			purpose,
			email_fingerprint,
			requester_fingerprint,
			otp_digest,
			attempt_count,
			max_attempts,
			expires_at,
			resend_not_before,
			delivery_status,
			sent_at,
			verified_at,
			invalidated_at,
			created_at
		FROM email_verification_challenges
		WHERE id = $1
		FOR UPDATE
	`, id)

	var challenge Challenge
	var requesterFingerprint []byte
	err := row.Scan(
		&challenge.ID,
		&challenge.Purpose,
		&challenge.EmailFingerprint,
		&requesterFingerprint,
		&challenge.OTPDigest,
		&challenge.AttemptCount,
		&challenge.MaxAttempts,
		&challenge.ExpiresAt,
		&challenge.ResendNotBefore,
		&challenge.DeliveryStatus,
		&challenge.SentAt,
		&challenge.VerifiedAt,
		&challenge.InvalidatedAt,
		&challenge.CreatedAt,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return Challenge{}, ErrChallengeNotFound
		}

		return Challenge{}, fmt.Errorf("query challenge for verification: %w", err)
	}
	challenge.RequesterFingerprint = requesterFingerprint

	return challenge, nil
}

func (transaction *postgresVerificationTransaction) IncrementAttempt(
	ctx context.Context,
	id string,
) error {
	commandTag, err := transaction.transaction.Exec(ctx, `
		UPDATE email_verification_challenges
		SET attempt_count = attempt_count + 1
		WHERE id = $1 AND attempt_count < max_attempts
	`, id)
	if err != nil {
		return fmt.Errorf("increment challenge attempt: %w", err)
	}
	if commandTag.RowsAffected() != 1 {
		return ErrUnexpectedChallengeState
	}

	return nil
}

func (transaction *postgresVerificationTransaction) MarkVerified(
	ctx context.Context,
	id string,
	verifiedAt time.Time,
) error {
	commandTag, err := transaction.transaction.Exec(ctx, `
		UPDATE email_verification_challenges
		SET verified_at = $2
		WHERE id = $1
			AND verified_at IS NULL
			AND invalidated_at IS NULL
	`, id, verifiedAt)
	if err != nil {
		return fmt.Errorf("mark challenge verified: %w", err)
	}
	if commandTag.RowsAffected() != 1 {
		return ErrUnexpectedChallengeState
	}

	return nil
}

func (transaction *postgresVerificationTransaction) Commit(ctx context.Context) error {
	return transaction.transaction.Commit(ctx)
}

func (transaction *postgresVerificationTransaction) Rollback(ctx context.Context) error {
	return transaction.transaction.Rollback(ctx)
}

type rateLimitQuery struct {
	query  string
	args   []any
	window time.Duration
	limit  int
}

func challengeRetryAt(
	ctx context.Context,
	transaction pgx.Tx,
	challenge Challenge,
	policy StartPolicy,
) (time.Time, error) {
	retryAt := challenge.CreatedAt

	var latestStart time.Time
	err := transaction.QueryRow(ctx, `
		SELECT created_at
		FROM email_verification_challenges
		WHERE email_fingerprint = $1
		ORDER BY created_at DESC
		LIMIT 1
	`, challenge.EmailFingerprint).Scan(&latestStart)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, fmt.Errorf("query resend cooldown: %w", err)
	}
	if err == nil {
		retryAt = laterTime(retryAt, latestStart.Add(policy.ResendCooldown))
	}

	queries := []rateLimitQuery{
		{
			query: `
				SELECT created_at
				FROM email_verification_challenges
				WHERE email_fingerprint = $1 AND created_at >= $2
				ORDER BY created_at
			`,
			args:   []any{challenge.EmailFingerprint, challenge.CreatedAt.Add(-policy.EmailWindow)},
			window: policy.EmailWindow,
			limit:  policy.MaxStartsPerEmail,
		},
		{
			query: `
				SELECT created_at
				FROM email_verification_challenges
				WHERE created_at >= $1
				ORDER BY created_at
			`,
			args:   []any{challenge.CreatedAt.Add(-policy.GlobalWindow)},
			window: policy.GlobalWindow,
			limit:  policy.MaxGlobalStarts,
		},
	}

	if len(challenge.RequesterFingerprint) > 0 {
		queries = append(queries, rateLimitQuery{
			query: `
				SELECT created_at
				FROM email_verification_challenges
				WHERE requester_fingerprint = $1 AND created_at >= $2
				ORDER BY created_at
			`,
			args: []any{
				challenge.RequesterFingerprint,
				challenge.CreatedAt.Add(-policy.RequesterWindow),
			},
			window: policy.RequesterWindow,
			limit:  policy.MaxStartsPerIP,
		})
	}

	for _, query := range queries {
		timestamps, err := queryTimestamps(ctx, transaction, query.query, query.args...)
		if err != nil {
			return time.Time{}, err
		}
		if len(timestamps) >= query.limit {
			threshold := timestamps[len(timestamps)-query.limit].Add(query.window)
			retryAt = laterTime(retryAt, threshold)
		}
	}

	return retryAt, nil
}

func queryTimestamps(
	ctx context.Context,
	transaction pgx.Tx,
	query string,
	arguments ...any,
) ([]time.Time, error) {
	rows, err := transaction.Query(ctx, query, arguments...)
	if err != nil {
		return nil, fmt.Errorf("query challenge rate limit: %w", err)
	}
	defer rows.Close()

	values := make([]time.Time, 0)
	for rows.Next() {
		var value time.Time
		if err := rows.Scan(&value); err != nil {
			return nil, fmt.Errorf("scan challenge rate limit: %w", err)
		}
		values = append(values, value)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate challenge rate limit: %w", err)
	}

	return values, nil
}

func nullableBytes(value []byte) any {
	if len(value) == 0 {
		return nil
	}

	return value
}

func laterTime(first time.Time, second time.Time) time.Time {
	if second.After(first) {
		return second
	}

	return first
}
