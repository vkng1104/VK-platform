//go:build integration

package emailverification_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/vkng1104/VK-platform/apps/api/internal/emailverification"
)

const integrationTimeout = 10 * time.Second

func TestPostgreSQLRepositoryRateLimitAndSupersessionIntegration(t *testing.T) {
	pool := newEmailVerificationDatabase(t, true)
	repository := newPostgreSQLRepository(t, pool)
	policy := emailverification.DefaultStartPolicy()
	first := integrationChallenge("123e4567-e89b-42d3-a456-426614174000", testNow, 1)

	ctx, cancel := context.WithTimeout(context.Background(), integrationTimeout)
	defer cancel()
	if err := repository.CreatePending(ctx, first, policy); err != nil {
		t.Fatalf("CreatePending(first) error = %v", err)
	}
	if err := repository.MarkSent(ctx, first.ID, first.CreatedAt.Add(time.Second)); err != nil {
		t.Fatalf("MarkSent(first) error = %v", err)
	}

	tooSoon := integrationChallenge("223e4567-e89b-42d3-a456-426614174000", testNow.Add(30*time.Second), 1)
	err := repository.CreatePending(ctx, tooSoon, policy)
	if !errors.Is(err, emailverification.ErrRateLimited) {
		t.Fatalf("CreatePending(too soon) error = %v", err)
	}
	var rateLimitError *emailverification.RateLimitError
	if !errors.As(err, &rateLimitError) || !rateLimitError.RetryAt.Equal(testNow.Add(time.Minute)) {
		t.Fatalf("rate limit = %#v", rateLimitError)
	}

	replacement := integrationChallenge("323e4567-e89b-42d3-a456-426614174000", testNow.Add(61*time.Second), 1)
	if err := repository.CreatePending(ctx, replacement, policy); err != nil {
		t.Fatalf("CreatePending(replacement) error = %v", err)
	}

	var invalidatedAt *time.Time
	if err := pool.QueryRow(ctx, `
		SELECT invalidated_at
		FROM email_verification_challenges
		WHERE id = $1
	`, first.ID).Scan(&invalidatedAt); err != nil {
		t.Fatalf("query superseded challenge: %v", err)
	}
	if invalidatedAt == nil || !invalidatedAt.Equal(replacement.CreatedAt) {
		t.Fatalf("invalidated_at = %v, want %s", invalidatedAt, replacement.CreatedAt)
	}

	third := integrationChallenge("423e4567-e89b-42d3-a456-426614174000", testNow.Add(122*time.Second), 1)
	if err := repository.CreatePending(ctx, third, policy); err != nil {
		t.Fatalf("CreatePending(third) error = %v", err)
	}
	fourth := integrationChallenge("523e4567-e89b-42d3-a456-426614174000", testNow.Add(183*time.Second), 1)
	err = repository.CreatePending(ctx, fourth, policy)
	if !errors.Is(err, emailverification.ErrRateLimited) {
		t.Fatalf("CreatePending(email quota) error = %v", err)
	}
	if !errors.As(err, &rateLimitError) || !rateLimitError.RetryAt.Equal(testNow.Add(policy.EmailWindow)) {
		t.Fatalf("email quota retry = %#v", rateLimitError)
	}

	var challengeCount int
	if err := pool.QueryRow(ctx, `SELECT COUNT(*) FROM email_verification_challenges`).Scan(&challengeCount); err != nil {
		t.Fatalf("count challenges: %v", err)
	}
	if challengeCount != 3 {
		t.Fatalf("challenge count = %d, want 3", challengeCount)
	}
}

func TestPostgreSQLVerificationIsSingleUseUnderConcurrencyIntegration(t *testing.T) {
	pool := newEmailVerificationDatabase(t, true)
	repository := newPostgreSQLRepository(t, pool)
	sender := &senderFake{}
	service := newService(t, repository, sender)

	ctx, cancel := context.WithTimeout(context.Background(), integrationTimeout)
	defer cancel()
	start, err := service.Start(ctx, validStartRequest())
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}

	startGate := make(chan struct{})
	results := make(chan error, 2)
	var waitGroup sync.WaitGroup
	for range 2 {
		waitGroup.Add(1)
		go func() {
			defer waitGroup.Done()
			<-startGate
			_, verifyErr := service.Verify(ctx, emailverification.VerifyRequest{ID: start.ID, Code: testCode})
			results <- verifyErr
		}()
	}
	close(startGate)
	waitGroup.Wait()
	close(results)

	successes := 0
	rejections := 0
	for verifyErr := range results {
		switch {
		case verifyErr == nil:
			successes++
		case errors.Is(verifyErr, emailverification.ErrInvalidOrExpiredCode):
			rejections++
		default:
			t.Fatalf("Verify() unexpected error = %v", verifyErr)
		}
	}
	if successes != 1 || rejections != 1 {
		t.Fatalf("concurrent results: successes=%d rejections=%d", successes, rejections)
	}

	var verifiedAt *time.Time
	if err := pool.QueryRow(ctx, `
		SELECT verified_at
		FROM email_verification_challenges
		WHERE id = $1
	`, start.ID).Scan(&verifiedAt); err != nil {
		t.Fatalf("query verified challenge: %v", err)
	}
	if verifiedAt == nil || !verifiedAt.Equal(testNow) {
		t.Fatalf("verified_at = %v", verifiedAt)
	}
}

func TestPostgreSQLRepositoryAttemptAndCleanupIntegration(t *testing.T) {
	pool := newEmailVerificationDatabase(t, true)
	repository := newPostgreSQLRepository(t, pool)
	policy := emailverification.DefaultStartPolicy()
	challenge := integrationChallenge(testChallengeID, testNow.Add(-time.Hour), 2)

	ctx, cancel := context.WithTimeout(context.Background(), integrationTimeout)
	defer cancel()
	if err := repository.CreatePending(ctx, challenge, policy); err != nil {
		t.Fatalf("CreatePending() error = %v", err)
	}
	if err := repository.MarkSent(ctx, challenge.ID, challenge.CreatedAt.Add(time.Second)); err != nil {
		t.Fatalf("MarkSent() error = %v", err)
	}

	transaction, err := repository.BeginVerification(ctx)
	if err != nil {
		t.Fatalf("BeginVerification() error = %v", err)
	}
	loaded, err := transaction.FindChallengeForUpdate(ctx, challenge.ID)
	if err != nil {
		t.Fatalf("FindChallengeForUpdate() error = %v", err)
	}
	if !bytes.Equal(loaded.OTPDigest, challenge.OTPDigest) || loaded.MaxAttempts != challenge.MaxAttempts {
		t.Fatalf("loaded challenge = %#v", loaded)
	}
	if err := transaction.IncrementAttempt(ctx, challenge.ID); err != nil {
		t.Fatalf("IncrementAttempt() error = %v", err)
	}
	if err := transaction.Commit(ctx); err != nil {
		t.Fatalf("Commit() error = %v", err)
	}

	var attempts int
	if err := pool.QueryRow(ctx, `
		SELECT attempt_count
		FROM email_verification_challenges
		WHERE id = $1
	`, challenge.ID).Scan(&attempts); err != nil {
		t.Fatalf("query attempt count: %v", err)
	}
	if attempts != 1 {
		t.Fatalf("attempt count = %d, want 1", attempts)
	}

	deleted, err := repository.DeleteExpired(ctx, testNow)
	if err != nil {
		t.Fatalf("DeleteExpired() error = %v", err)
	}
	if deleted != 1 {
		t.Fatalf("DeleteExpired() = %d, want 1", deleted)
	}
}

func TestEmailVerificationMigrationConstraintsAndReversibilityIntegration(t *testing.T) {
	pool := newEmailVerificationDatabase(t, false)
	executeEmailVerificationMigration(t, pool, "000002_create_email_verification_challenges.up.sql")
	assertEmailVerificationTable(t, pool, true)

	ctx, cancel := context.WithTimeout(context.Background(), integrationTimeout)
	_, err := pool.Exec(ctx, `
		INSERT INTO email_verification_challenges (
			id, purpose, email_fingerprint, otp_digest, max_attempts,
			expires_at, resend_not_before, delivery_status, created_at
		)
		VALUES ($1, $2, $3, $4, 5, $5, $6, 'pending', $7)
	`,
		testChallengeID,
		emailverification.PurposeRestrictedResourceAccess,
		[]byte("too-short"),
		bytes.Repeat([]byte{2}, 32),
		testNow.Add(time.Minute),
		testNow.Add(time.Second),
		testNow,
	)
	cancel()
	var postgresError *pgconn.PgError
	if !errors.As(err, &postgresError) || postgresError.Code != "23514" ||
		postgresError.ConstraintName != "email_verification_challenges_email_fingerprint_check" {
		t.Fatalf("constraint error = %v", err)
	}

	executeEmailVerificationMigration(t, pool, "000002_create_email_verification_challenges.down.sql")
	assertEmailVerificationTable(t, pool, false)
	executeEmailVerificationMigration(t, pool, "000002_create_email_verification_challenges.up.sql")
	assertEmailVerificationTable(t, pool, true)
}

func newPostgreSQLRepository(
	t *testing.T,
	pool *pgxpool.Pool,
) *emailverification.PostgreSQLRepository {
	t.Helper()

	repository, err := emailverification.NewPostgreSQLRepository(pool)
	if err != nil {
		t.Fatalf("NewPostgreSQLRepository() error = %v", err)
	}
	return repository
}

func integrationChallenge(id string, createdAt time.Time, fingerprintByte byte) emailverification.Challenge {
	return emailverification.Challenge{
		ID:                   id,
		Purpose:              emailverification.PurposeRestrictedResourceAccess,
		EmailFingerprint:     bytes.Repeat([]byte{fingerprintByte}, 32),
		RequesterFingerprint: bytes.Repeat([]byte{fingerprintByte + 10}, 32),
		OTPDigest:            bytes.Repeat([]byte{fingerprintByte + 20}, 32),
		MaxAttempts:          5,
		ExpiresAt:            createdAt.Add(5 * time.Minute),
		ResendNotBefore:      createdAt.Add(time.Minute),
		DeliveryStatus:       emailverification.DeliveryStatusPending,
		CreatedAt:            createdAt,
	}
}

func newEmailVerificationDatabase(t *testing.T, migrate bool) *pgxpool.Pool {
	t.Helper()

	databaseURL := strings.TrimSpace(os.Getenv("TEST_DATABASE_URL"))
	if databaseURL == "" {
		t.Skip("TEST_DATABASE_URL is required for PostgreSQL integration tests")
	}

	ctx, cancel := context.WithTimeout(context.Background(), integrationTimeout)
	defer cancel()
	adminConfiguration, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		t.Fatalf("parse TEST_DATABASE_URL: %v", err)
	}
	adminPool, err := pgxpool.NewWithConfig(ctx, adminConfiguration)
	if err != nil {
		t.Fatalf("open integration admin database: %v", err)
	}
	if err := adminPool.Ping(ctx); err != nil {
		adminPool.Close()
		t.Fatalf("ping integration database: %v", err)
	}

	schema := newEmailVerificationSchema(t)
	quotedSchema := pgx.Identifier{schema}.Sanitize()
	if _, err := adminPool.Exec(ctx, "CREATE SCHEMA "+quotedSchema); err != nil {
		adminPool.Close()
		t.Fatalf("create integration schema: %v", err)
	}

	testConfiguration, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		adminPool.Close()
		t.Fatalf("parse test pool configuration: %v", err)
	}
	testConfiguration.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, testConfiguration)
	if err != nil {
		adminPool.Close()
		t.Fatalf("open schema test pool: %v", err)
	}

	t.Cleanup(func() {
		pool.Close()
		cleanupContext, cleanupCancel := context.WithTimeout(context.Background(), integrationTimeout)
		defer cleanupCancel()
		if _, err := adminPool.Exec(cleanupContext, "DROP SCHEMA IF EXISTS "+quotedSchema+" CASCADE"); err != nil {
			t.Errorf("drop integration schema: %v", err)
		}
		adminPool.Close()
	})

	if migrate {
		executeEmailVerificationMigration(t, pool, "000002_create_email_verification_challenges.up.sql")
	}
	return pool
}

func newEmailVerificationSchema(t *testing.T) string {
	t.Helper()

	var randomBytes [8]byte
	if _, err := rand.Read(randomBytes[:]); err != nil {
		t.Fatalf("generate schema suffix: %v", err)
	}
	return "email_verification_test_" + hex.EncodeToString(randomBytes[:])
}

func executeEmailVerificationMigration(t *testing.T, pool *pgxpool.Pool, filename string) {
	t.Helper()

	_, currentFile, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("locate integration test file")
	}
	migrationPath := filepath.Join(filepath.Dir(currentFile), "..", "..", "migrations", filename)
	contents, err := os.ReadFile(migrationPath)
	if err != nil {
		t.Fatalf("read migration %s: %v", filename, err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), integrationTimeout)
	defer cancel()
	if _, err := pool.Exec(ctx, string(contents)); err != nil {
		t.Fatalf("execute migration %s: %v", filename, err)
	}
}

func assertEmailVerificationTable(t *testing.T, pool *pgxpool.Pool, exists bool) {
	t.Helper()

	ctx, cancel := context.WithTimeout(context.Background(), integrationTimeout)
	defer cancel()
	var tableName *string
	if err := pool.QueryRow(ctx, `SELECT TO_REGCLASS('email_verification_challenges')::text`).Scan(&tableName); err != nil {
		t.Fatalf("query email verification table: %v", err)
	}
	if (tableName != nil) != exists {
		t.Fatalf("table exists = %t, want %t", tableName != nil, exists)
	}
}
