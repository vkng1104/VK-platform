package emailverification_test

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"errors"
	"reflect"
	"testing"
	"time"

	"github.com/vkng1104/VK-platform/apps/api/internal/emailverification"
)

const (
	testChallengeID = "123e4567-e89b-42d3-a456-426614174000"
	testCode        = "123456"
)

var (
	testOTPPepper      = []byte("otp-pepper-with-at-least-32-characters")
	testFingerprintKey = []byte("fingerprint-key-with-at-least-32-chars")
	testNow            = time.Date(2026, time.October, 6, 9, 0, 0, 0, time.UTC)
)

func TestServiceStartCreatesAndDeliversPurposeBoundChallenge(t *testing.T) {
	t.Parallel()

	repository := &repositoryFake{}
	sender := &senderFake{}
	service := newService(t, repository, sender)

	result, err := service.Start(context.Background(), emailverification.StartRequest{
		Email:            "Visitor@Example.COM",
		Purpose:          emailverification.PurposeRestrictedResourceAccess,
		RequesterAddress: "192.0.2.10",
	})
	if err != nil {
		t.Fatalf("Start() error = %v", err)
	}

	if result.ID != testChallengeID {
		t.Errorf("Start() ID = %q, want %q", result.ID, testChallengeID)
	}
	if result.MaskedEmail != "V*****r@example.com" {
		t.Errorf("Start() masked email = %q", result.MaskedEmail)
	}
	if !result.ExpiresAt.Equal(testNow.Add(5 * time.Minute)) {
		t.Errorf("Start() expiry = %s", result.ExpiresAt)
	}
	if !result.ResendNotBefore.Equal(testNow.Add(time.Minute)) {
		t.Errorf("Start() resend time = %s", result.ResendNotBefore)
	}

	challenge := repository.created
	if challenge.ID != testChallengeID || challenge.Purpose != emailverification.PurposeRestrictedResourceAccess {
		t.Fatalf("created challenge = %#v", challenge)
	}
	if challenge.DeliveryStatus != emailverification.DeliveryStatusPending {
		t.Errorf("delivery status = %q", challenge.DeliveryStatus)
	}
	if challenge.MaxAttempts != 5 || challenge.AttemptCount != 0 {
		t.Errorf("attempt policy = %d/%d", challenge.AttemptCount, challenge.MaxAttempts)
	}
	if got, want := challenge.EmailFingerprint, testDigest(testFingerprintKey, "Visitor@example.com"); !hmac.Equal(got, want) {
		t.Error("email fingerprint did not use the normalized address")
	}
	if got, want := challenge.RequesterFingerprint, testDigest(testFingerprintKey, "192.0.2.10"); !hmac.Equal(got, want) {
		t.Error("requester fingerprint did not use the requester address")
	}
	if got, want := challenge.OTPDigest, testDigest(
		testOTPPepper,
		testChallengeID,
		string(emailverification.PurposeRestrictedResourceAccess),
		testCode,
	); !hmac.Equal(got, want) {
		t.Error("OTP digest did not bind challenge ID, purpose, and code")
	}
	if string(challenge.OTPDigest) == testCode {
		t.Fatal("challenge stored the plaintext OTP")
	}

	if sender.calls != 1 {
		t.Fatalf("sender calls = %d, want 1", sender.calls)
	}
	wantMessage := emailverification.EmailMessage{
		To:        "Visitor@example.com",
		Code:      testCode,
		ExpiresAt: testNow.Add(5 * time.Minute),
	}
	if !reflect.DeepEqual(sender.message, wantMessage) {
		t.Errorf("sent message = %#v, want %#v", sender.message, wantMessage)
	}
	if repository.markedSentID != testChallengeID || !repository.markedSentAt.Equal(testNow) {
		t.Errorf("MarkSent() = (%q, %s)", repository.markedSentID, repository.markedSentAt)
	}
}

func TestServiceStartRejectsInvalidInputBeforePersistence(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name    string
		request emailverification.StartRequest
		wantErr error
	}{
		{
			name: "blank email",
			request: emailverification.StartRequest{
				Purpose: emailverification.PurposeRestrictedResourceAccess,
			},
			wantErr: emailverification.ErrInvalidEmail,
		},
		{
			name: "display-name email",
			request: emailverification.StartRequest{
				Email:   "Visitor <visitor@example.com>",
				Purpose: emailverification.PurposeRestrictedResourceAccess,
			},
			wantErr: emailverification.ErrInvalidEmail,
		},
		{
			name: "domain without public suffix",
			request: emailverification.StartRequest{
				Email:   "visitor@localhost",
				Purpose: emailverification.PurposeRestrictedResourceAccess,
			},
			wantErr: emailverification.ErrInvalidEmail,
		},
		{
			name: "unsupported purpose",
			request: emailverification.StartRequest{
				Email:   "visitor@example.com",
				Purpose: "password_reset",
			},
			wantErr: emailverification.ErrInvalidPurpose,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			repository := &repositoryFake{}
			sender := &senderFake{}
			service := newService(t, repository, sender)

			_, err := service.Start(context.Background(), test.request)
			if !errors.Is(err, test.wantErr) {
				t.Fatalf("Start() error = %v, want %v", err, test.wantErr)
			}
			if repository.createCalls != 0 || sender.calls != 0 {
				t.Fatalf("invalid request caused persistence or delivery: repository=%d sender=%d", repository.createCalls, sender.calls)
			}
		})
	}
}

func TestServiceStartPreservesRateLimitAndSkipsDelivery(t *testing.T) {
	t.Parallel()

	retryAt := testNow.Add(time.Minute)
	repository := &repositoryFake{createErr: &emailverification.RateLimitError{RetryAt: retryAt}}
	sender := &senderFake{}
	service := newService(t, repository, sender)

	_, err := service.Start(context.Background(), validStartRequest())
	if !errors.Is(err, emailverification.ErrRateLimited) {
		t.Fatalf("Start() error = %v, want ErrRateLimited", err)
	}
	var rateLimitError *emailverification.RateLimitError
	if !errors.As(err, &rateLimitError) || !rateLimitError.RetryAt.Equal(retryAt) {
		t.Fatalf("Start() rate-limit error = %#v", rateLimitError)
	}
	if sender.calls != 0 {
		t.Fatalf("sender calls = %d, want 0", sender.calls)
	}
}

func TestServiceStartMarksFailedDelivery(t *testing.T) {
	t.Parallel()

	senderFailure := errors.New("provider secret must not escape")
	repository := &repositoryFake{}
	sender := &senderFake{err: senderFailure}
	service := newService(t, repository, sender)

	_, err := service.Start(context.Background(), validStartRequest())
	if !errors.Is(err, emailverification.ErrDeliveryUnavailable) {
		t.Fatalf("Start() error = %v, want ErrDeliveryUnavailable", err)
	}
	if repository.markedFailedID != testChallengeID {
		t.Fatalf("MarkFailed() ID = %q", repository.markedFailedID)
	}
}

func TestServiceVerifySucceedsOnceThroughTransaction(t *testing.T) {
	t.Parallel()

	transaction := &verificationTransactionFake{challenge: validSentChallenge(testCode)}
	repository := &repositoryFake{transaction: transaction}
	service := newService(t, repository, &senderFake{})

	result, err := service.Verify(context.Background(), emailverification.VerifyRequest{
		ID:   testChallengeID,
		Code: testCode,
	})
	if err != nil {
		t.Fatalf("Verify() error = %v", err)
	}
	if result.ID != testChallengeID || result.Purpose != emailverification.PurposeRestrictedResourceAccess ||
		!result.VerifiedAt.Equal(testNow) {
		t.Fatalf("Verify() result = %#v", result)
	}
	if transaction.markedVerifiedID != testChallengeID || !transaction.markedVerifiedAt.Equal(testNow) {
		t.Errorf("MarkVerified() = (%q, %s)", transaction.markedVerifiedID, transaction.markedVerifiedAt)
	}
	if !transaction.committed || transaction.incremented {
		t.Errorf("transaction committed=%t incremented=%t", transaction.committed, transaction.incremented)
	}
}

func TestServiceVerifyRecordsWrongAttempt(t *testing.T) {
	t.Parallel()

	transaction := &verificationTransactionFake{challenge: validSentChallenge(testCode)}
	service := newService(t, &repositoryFake{transaction: transaction}, &senderFake{})

	_, err := service.Verify(context.Background(), emailverification.VerifyRequest{
		ID:   testChallengeID,
		Code: "654321",
	})
	if !errors.Is(err, emailverification.ErrInvalidOrExpiredCode) {
		t.Fatalf("Verify() error = %v", err)
	}
	if !transaction.incremented || !transaction.committed || transaction.markedVerifiedID != "" {
		t.Fatalf("wrong-code transaction = %#v", transaction)
	}
}

func TestServiceVerifyHidesUnavailableChallengeStates(t *testing.T) {
	t.Parallel()

	verifiedAt := testNow.Add(-time.Minute)
	invalidatedAt := testNow.Add(-time.Minute)
	tests := []struct {
		name      string
		challenge emailverification.Challenge
	}{
		{name: "pending delivery", challenge: mutateChallenge(validSentChallenge(testCode), func(value *emailverification.Challenge) {
			value.DeliveryStatus = emailverification.DeliveryStatusPending
		})},
		{name: "expired", challenge: mutateChallenge(validSentChallenge(testCode), func(value *emailverification.Challenge) {
			value.ExpiresAt = testNow
		})},
		{name: "attempts exhausted", challenge: mutateChallenge(validSentChallenge(testCode), func(value *emailverification.Challenge) {
			value.AttemptCount = value.MaxAttempts
		})},
		{name: "already verified", challenge: mutateChallenge(validSentChallenge(testCode), func(value *emailverification.Challenge) {
			value.VerifiedAt = &verifiedAt
		})},
		{name: "invalidated", challenge: mutateChallenge(validSentChallenge(testCode), func(value *emailverification.Challenge) {
			value.InvalidatedAt = &invalidatedAt
		})},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			transaction := &verificationTransactionFake{challenge: test.challenge}
			service := newService(t, &repositoryFake{transaction: transaction}, &senderFake{})
			_, err := service.Verify(context.Background(), emailverification.VerifyRequest{
				ID:   testChallengeID,
				Code: testCode,
			})
			if !errors.Is(err, emailverification.ErrInvalidOrExpiredCode) {
				t.Fatalf("Verify() error = %v", err)
			}
			if transaction.incremented || transaction.committed || transaction.markedVerifiedID != "" {
				t.Fatalf("terminal challenge mutated: %#v", transaction)
			}
		})
	}
}

func TestServiceVerifyMapsMissingChallengeToGenericError(t *testing.T) {
	t.Parallel()

	transaction := &verificationTransactionFake{findErr: emailverification.ErrChallengeNotFound}
	service := newService(t, &repositoryFake{transaction: transaction}, &senderFake{})

	_, err := service.Verify(context.Background(), emailverification.VerifyRequest{
		ID:   testChallengeID,
		Code: testCode,
	})
	if !errors.Is(err, emailverification.ErrInvalidOrExpiredCode) {
		t.Fatalf("Verify() error = %v", err)
	}
}

func TestServiceVerifyRejectsMalformedInputBeforeTransaction(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name    string
		request emailverification.VerifyRequest
		wantErr error
	}{
		{name: "invalid ID", request: emailverification.VerifyRequest{ID: "not-a-uuid", Code: testCode}, wantErr: emailverification.ErrInvalidChallengeID},
		{name: "short code", request: emailverification.VerifyRequest{ID: testChallengeID, Code: "12345"}, wantErr: emailverification.ErrInvalidCode},
		{name: "non-numeric code", request: emailverification.VerifyRequest{ID: testChallengeID, Code: "12ab56"}, wantErr: emailverification.ErrInvalidCode},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			repository := &repositoryFake{}
			service := newService(t, repository, &senderFake{})
			_, err := service.Verify(context.Background(), test.request)
			if !errors.Is(err, test.wantErr) {
				t.Fatalf("Verify() error = %v, want %v", err, test.wantErr)
			}
			if repository.beginCalls != 0 {
				t.Fatalf("BeginVerification() calls = %d", repository.beginCalls)
			}
		})
	}
}

func TestNewServiceValidatesDependenciesAndSecrets(t *testing.T) {
	t.Parallel()

	validDependencies := emailverification.ServiceDependencies{
		Repository:    &repositoryFake{},
		Sender:        &senderFake{},
		Clock:         func() time.Time { return testNow },
		IDGenerator:   func() (string, error) { return testChallengeID, nil },
		CodeGenerator: func() (string, error) { return testCode, nil },
	}
	validConfig := emailverification.ServiceConfig{
		OTPPepper:      testOTPPepper,
		FingerprintKey: testFingerprintKey,
		Policy:         emailverification.DefaultStartPolicy(),
	}

	tests := []struct {
		name         string
		dependencies emailverification.ServiceDependencies
		config       emailverification.ServiceConfig
		wantErr      error
	}{
		{name: "missing repository", dependencies: mutateDependencies(validDependencies, func(value *emailverification.ServiceDependencies) { value.Repository = nil }), config: validConfig, wantErr: emailverification.ErrMissingRepository},
		{name: "missing sender", dependencies: mutateDependencies(validDependencies, func(value *emailverification.ServiceDependencies) { value.Sender = nil }), config: validConfig, wantErr: emailverification.ErrMissingSender},
		{name: "missing clock", dependencies: mutateDependencies(validDependencies, func(value *emailverification.ServiceDependencies) { value.Clock = nil }), config: validConfig, wantErr: emailverification.ErrMissingClock},
		{name: "short OTP pepper", dependencies: validDependencies, config: mutateConfig(validConfig, func(value *emailverification.ServiceConfig) { value.OTPPepper = []byte("short") }), wantErr: emailverification.ErrMissingOTPPepper},
		{name: "short fingerprint key", dependencies: validDependencies, config: mutateConfig(validConfig, func(value *emailverification.ServiceConfig) { value.FingerprintKey = []byte("short") }), wantErr: emailverification.ErrMissingFingerprintKey},
		{name: "invalid policy", dependencies: validDependencies, config: mutateConfig(validConfig, func(value *emailverification.ServiceConfig) { value.Policy.MaxAttempts = 0 }), wantErr: emailverification.ErrInvalidPolicy},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			_, err := emailverification.NewService(test.dependencies, test.config)
			if !errors.Is(err, test.wantErr) {
				t.Fatalf("NewService() error = %v, want %v", err, test.wantErr)
			}
		})
	}
}

type repositoryFake struct {
	created        emailverification.Challenge
	createCalls    int
	createErr      error
	markedSentID   string
	markedSentAt   time.Time
	markSentErr    error
	markedFailedID string
	markFailedErr  error
	transaction    emailverification.VerificationTransaction
	beginCalls     int
	beginErr       error
}

func (repository *repositoryFake) CreatePending(
	_ context.Context,
	challenge emailverification.Challenge,
	_ emailverification.StartPolicy,
) error {
	repository.createCalls++
	repository.created = challenge
	return repository.createErr
}

func (repository *repositoryFake) MarkSent(_ context.Context, id string, sentAt time.Time) error {
	repository.markedSentID = id
	repository.markedSentAt = sentAt
	return repository.markSentErr
}

func (repository *repositoryFake) MarkFailed(_ context.Context, id string) error {
	repository.markedFailedID = id
	return repository.markFailedErr
}

func (repository *repositoryFake) BeginVerification(context.Context) (emailverification.VerificationTransaction, error) {
	repository.beginCalls++
	if repository.beginErr != nil {
		return nil, repository.beginErr
	}
	if repository.transaction == nil {
		return &verificationTransactionFake{}, nil
	}
	return repository.transaction, nil
}

type senderFake struct {
	message emailverification.EmailMessage
	calls   int
	err     error
}

func (sender *senderFake) SendVerificationCode(
	_ context.Context,
	message emailverification.EmailMessage,
) error {
	sender.calls++
	sender.message = message
	return sender.err
}

type verificationTransactionFake struct {
	challenge        emailverification.Challenge
	findErr          error
	incremented      bool
	incrementErr     error
	markedVerifiedID string
	markedVerifiedAt time.Time
	markVerifiedErr  error
	committed        bool
	commitErr        error
	rolledBack       bool
}

func (transaction *verificationTransactionFake) FindChallengeForUpdate(
	context.Context,
	string,
) (emailverification.Challenge, error) {
	return transaction.challenge, transaction.findErr
}

func (transaction *verificationTransactionFake) IncrementAttempt(context.Context, string) error {
	transaction.incremented = true
	return transaction.incrementErr
}

func (transaction *verificationTransactionFake) MarkVerified(
	_ context.Context,
	id string,
	verifiedAt time.Time,
) error {
	transaction.markedVerifiedID = id
	transaction.markedVerifiedAt = verifiedAt
	return transaction.markVerifiedErr
}

func (transaction *verificationTransactionFake) Commit(context.Context) error {
	transaction.committed = true
	return transaction.commitErr
}

func (transaction *verificationTransactionFake) Rollback(context.Context) error {
	transaction.rolledBack = true
	return nil
}

func newService(
	t *testing.T,
	repository emailverification.Repository,
	sender emailverification.Sender,
) *emailverification.Service {
	t.Helper()

	service, err := emailverification.NewService(
		emailverification.ServiceDependencies{
			Repository:    repository,
			Sender:        sender,
			Clock:         func() time.Time { return testNow },
			IDGenerator:   func() (string, error) { return testChallengeID, nil },
			CodeGenerator: func() (string, error) { return testCode, nil },
		},
		emailverification.ServiceConfig{
			OTPPepper:      testOTPPepper,
			FingerprintKey: testFingerprintKey,
			Policy:         emailverification.DefaultStartPolicy(),
		},
	)
	if err != nil {
		t.Fatalf("NewService() error = %v", err)
	}
	return service
}

func validStartRequest() emailverification.StartRequest {
	return emailverification.StartRequest{
		Email:            "visitor@example.com",
		Purpose:          emailverification.PurposeRestrictedResourceAccess,
		RequesterAddress: "192.0.2.10",
	}
}

func validSentChallenge(code string) emailverification.Challenge {
	return emailverification.Challenge{
		ID:             testChallengeID,
		Purpose:        emailverification.PurposeRestrictedResourceAccess,
		OTPDigest:      testDigest(testOTPPepper, testChallengeID, string(emailverification.PurposeRestrictedResourceAccess), code),
		AttemptCount:   0,
		MaxAttempts:    5,
		ExpiresAt:      testNow.Add(time.Minute),
		DeliveryStatus: emailverification.DeliveryStatusSent,
		CreatedAt:      testNow.Add(-time.Minute),
	}
}

func testDigest(key []byte, values ...string) []byte {
	digester := hmac.New(sha256.New, key)
	for index, value := range values {
		if index > 0 {
			digester.Write([]byte{0})
		}
		digester.Write([]byte(value))
	}
	return digester.Sum(nil)
}

func mutateChallenge(
	value emailverification.Challenge,
	mutate func(*emailverification.Challenge),
) emailverification.Challenge {
	mutate(&value)
	return value
}

func mutateDependencies(
	value emailverification.ServiceDependencies,
	mutate func(*emailverification.ServiceDependencies),
) emailverification.ServiceDependencies {
	mutate(&value)
	return value
}

func mutateConfig(
	value emailverification.ServiceConfig,
	mutate func(*emailverification.ServiceConfig),
) emailverification.ServiceConfig {
	mutate(&value)
	return value
}
