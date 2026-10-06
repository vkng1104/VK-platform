package emailverification

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"math/big"
	"net/mail"
	"regexp"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	maximumEmailLength     = 254
	minimumSecretLength    = 32
	oneTimeCodeUpperBound  = 1_000_000
	stateTransitionTimeout = 3 * time.Second
)

var (
	challengeIDPattern = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	oneTimeCodePattern = regexp.MustCompile(`^[0-9]{6}$`)
)

type Repository interface {
	CreatePending(ctx context.Context, challenge Challenge, policy StartPolicy) error
	MarkSent(ctx context.Context, id string, sentAt time.Time) error
	MarkFailed(ctx context.Context, id string) error
	BeginVerification(ctx context.Context) (VerificationTransaction, error)
}

type VerificationTransaction interface {
	FindChallengeForUpdate(ctx context.Context, id string) (Challenge, error)
	IncrementAttempt(ctx context.Context, id string) error
	MarkVerified(ctx context.Context, id string, verifiedAt time.Time) error
	Commit(ctx context.Context) error
	Rollback(ctx context.Context) error
}

type Clock func() time.Time
type IDGenerator func() (string, error)
type CodeGenerator func() (string, error)

type ServiceDependencies struct {
	Repository    Repository
	Sender        Sender
	Clock         Clock
	IDGenerator   IDGenerator
	CodeGenerator CodeGenerator
}

type ServiceConfig struct {
	OTPPepper      []byte
	FingerprintKey []byte
	Policy         StartPolicy
}

type Service struct {
	repository     Repository
	sender         Sender
	clock          Clock
	idGenerator    IDGenerator
	codeGenerator  CodeGenerator
	otpPepper      []byte
	fingerprintKey []byte
	policy         StartPolicy
}

func NewService(dependencies ServiceDependencies, configuration ServiceConfig) (*Service, error) {
	if dependencies.Repository == nil {
		return nil, ErrMissingRepository
	}
	if dependencies.Sender == nil {
		return nil, ErrMissingSender
	}
	if dependencies.Clock == nil {
		return nil, ErrMissingClock
	}
	if dependencies.IDGenerator == nil {
		return nil, ErrMissingIDGenerator
	}
	if dependencies.CodeGenerator == nil {
		return nil, ErrMissingCodeGenerator
	}
	if len(configuration.OTPPepper) < minimumSecretLength {
		return nil, ErrMissingOTPPepper
	}
	if len(configuration.FingerprintKey) < minimumSecretLength {
		return nil, ErrMissingFingerprintKey
	}
	if err := validatePolicy(configuration.Policy); err != nil {
		return nil, err
	}

	return &Service{
		repository:     dependencies.Repository,
		sender:         dependencies.Sender,
		clock:          dependencies.Clock,
		idGenerator:    dependencies.IDGenerator,
		codeGenerator:  dependencies.CodeGenerator,
		otpPepper:      append([]byte(nil), configuration.OTPPepper...),
		fingerprintKey: append([]byte(nil), configuration.FingerprintKey...),
		policy:         configuration.Policy,
	}, nil
}

func (service *Service) Start(ctx context.Context, request StartRequest) (StartResult, error) {
	normalizedEmail, err := normalizeEmail(request.Email)
	if err != nil {
		return StartResult{}, err
	}
	if !validPurpose(request.Purpose) {
		return StartResult{}, ErrInvalidPurpose
	}

	requesterAddress := strings.TrimSpace(request.RequesterAddress)

	id, err := service.idGenerator()
	if err != nil {
		return StartResult{}, fmt.Errorf("generate challenge ID: %w", err)
	}
	if !challengeIDPattern.MatchString(id) {
		return StartResult{}, errors.New("generated challenge ID is invalid")
	}

	code, err := service.codeGenerator()
	if err != nil {
		return StartResult{}, fmt.Errorf("generate verification code: %w", err)
	}
	if !oneTimeCodePattern.MatchString(code) {
		return StartResult{}, errors.New("generated verification code is invalid")
	}

	now := service.clock().UTC()
	challenge := Challenge{
		ID:                   id,
		Purpose:              request.Purpose,
		EmailFingerprint:     keyedDigest(service.fingerprintKey, normalizedEmail),
		RequesterFingerprint: fingerprintOptional(service.fingerprintKey, requesterAddress),
		OTPDigest:            service.digestCode(id, request.Purpose, code),
		MaxAttempts:          service.policy.MaxAttempts,
		ExpiresAt:            now.Add(service.policy.ChallengeTTL),
		ResendNotBefore:      now.Add(service.policy.ResendCooldown),
		DeliveryStatus:       DeliveryStatusPending,
		CreatedAt:            now,
	}

	if err := service.repository.CreatePending(ctx, challenge, service.policy); err != nil {
		return StartResult{}, fmt.Errorf("create email verification challenge: %w", err)
	}

	message := EmailMessage{To: normalizedEmail, Code: code, ExpiresAt: challenge.ExpiresAt}
	if err := service.sender.SendVerificationCode(ctx, message); err != nil {
		transitionContext, cancelTransition := context.WithTimeout(
			context.WithoutCancel(ctx),
			stateTransitionTimeout,
		)
		markErr := service.repository.MarkFailed(transitionContext, challenge.ID)
		cancelTransition()
		if markErr != nil {
			return StartResult{}, errors.Join(
				ErrDeliveryUnavailable,
				fmt.Errorf("send verification email: %w", err),
				fmt.Errorf("mark verification delivery failed: %w", markErr),
			)
		}

		return StartResult{}, fmt.Errorf("send verification email: %w: %w", ErrDeliveryUnavailable, err)
	}

	sentAt := service.clock().UTC()
	transitionContext, cancelTransition := context.WithTimeout(
		context.WithoutCancel(ctx),
		stateTransitionTimeout,
	)
	err = service.repository.MarkSent(transitionContext, challenge.ID, sentAt)
	cancelTransition()
	if err != nil {
		return StartResult{}, fmt.Errorf("mark verification email sent: %w", err)
	}

	return StartResult{
		ID:              challenge.ID,
		MaskedEmail:     maskEmail(normalizedEmail),
		ExpiresAt:       challenge.ExpiresAt,
		ResendNotBefore: challenge.ResendNotBefore,
	}, nil
}

func (service *Service) Verify(
	ctx context.Context,
	request VerifyRequest,
) (VerificationResult, error) {
	id := strings.TrimSpace(request.ID)
	if !challengeIDPattern.MatchString(id) {
		return VerificationResult{}, ErrInvalidChallengeID
	}
	if !oneTimeCodePattern.MatchString(request.Code) {
		return VerificationResult{}, ErrInvalidCode
	}

	transaction, err := service.repository.BeginVerification(ctx)
	if err != nil {
		return VerificationResult{}, fmt.Errorf("begin email verification transaction: %w", err)
	}
	defer func() {
		rollbackContext, cancelRollback := context.WithTimeout(
			context.WithoutCancel(ctx),
			stateTransitionTimeout,
		)
		defer cancelRollback()
		_ = transaction.Rollback(rollbackContext)
	}()

	challenge, err := transaction.FindChallengeForUpdate(ctx, id)
	if err != nil {
		if errors.Is(err, ErrChallengeNotFound) {
			return VerificationResult{}, ErrInvalidOrExpiredCode
		}

		return VerificationResult{}, fmt.Errorf("load email verification challenge: %w", err)
	}

	now := service.clock().UTC()
	if challenge.DeliveryStatus != DeliveryStatusSent ||
		challenge.InvalidatedAt != nil ||
		challenge.VerifiedAt != nil ||
		!now.Before(challenge.ExpiresAt) ||
		challenge.AttemptCount >= challenge.MaxAttempts {
		return VerificationResult{}, ErrInvalidOrExpiredCode
	}

	candidateDigest := service.digestCode(challenge.ID, challenge.Purpose, request.Code)
	if !hmac.Equal(challenge.OTPDigest, candidateDigest) {
		if err := transaction.IncrementAttempt(ctx, challenge.ID); err != nil {
			return VerificationResult{}, fmt.Errorf("record email verification attempt: %w", err)
		}
		if err := transaction.Commit(ctx); err != nil {
			return VerificationResult{}, fmt.Errorf("commit email verification attempt: %w", err)
		}

		return VerificationResult{}, ErrInvalidOrExpiredCode
	}

	if err := transaction.MarkVerified(ctx, challenge.ID, now); err != nil {
		return VerificationResult{}, fmt.Errorf("mark email verification complete: %w", err)
	}
	if err := transaction.Commit(ctx); err != nil {
		return VerificationResult{}, fmt.Errorf("commit email verification: %w", err)
	}

	return VerificationResult{ID: challenge.ID, Purpose: challenge.Purpose, VerifiedAt: now}, nil
}

func NewSecureID() (string, error) {
	var value [16]byte
	if _, err := rand.Read(value[:]); err != nil {
		return "", fmt.Errorf("read secure random UUID bytes: %w", err)
	}

	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(value[:])

	return encoded[0:8] + "-" + encoded[8:12] + "-" + encoded[12:16] + "-" +
		encoded[16:20] + "-" + encoded[20:32], nil
}

func NewSixDigitCode() (string, error) {
	value, err := rand.Int(rand.Reader, big.NewInt(oneTimeCodeUpperBound))
	if err != nil {
		return "", fmt.Errorf("read secure random OTP: %w", err)
	}

	return fmt.Sprintf("%06d", value.Int64()), nil
}

func validatePolicy(policy StartPolicy) error {
	if policy.ChallengeTTL <= 0 || policy.ResendCooldown <= 0 || policy.EmailWindow <= 0 ||
		policy.MaxStartsPerEmail <= 0 || policy.RequesterWindow <= 0 || policy.MaxStartsPerIP <= 0 ||
		policy.GlobalWindow <= 0 || policy.MaxGlobalStarts <= 0 || policy.MaxAttempts <= 0 {
		return ErrInvalidPolicy
	}

	return nil
}

func validPurpose(purpose Purpose) bool {
	return purpose == PurposeRestrictedResourceAccess
}

func normalizeEmail(value string) (string, error) {
	normalized := strings.TrimSpace(value)
	if normalized == "" || len(normalized) > maximumEmailLength || !utf8.ValidString(normalized) ||
		strings.ContainsAny(normalized, "\r\n") {
		return "", ErrInvalidEmail
	}

	parsed, err := mail.ParseAddress(normalized)
	if err != nil || parsed.Address != normalized {
		return "", ErrInvalidEmail
	}

	separator := strings.LastIndexByte(normalized, '@')
	if separator <= 0 || separator == len(normalized)-1 {
		return "", ErrInvalidEmail
	}

	localPart := normalized[:separator]
	domain := strings.ToLower(normalized[separator+1:])
	if strings.ContainsAny(localPart, "\"(),:;<>[\\]") || !strings.Contains(domain, ".") {
		return "", ErrInvalidEmail
	}

	return localPart + "@" + domain, nil
}

func maskEmail(value string) string {
	separator := strings.LastIndexByte(value, '@')
	localRunes := []rune(value[:separator])
	if len(localRunes) == 1 {
		return "*" + value[separator:]
	}

	return string(localRunes[0]) + strings.Repeat("*", len(localRunes)-2) +
		string(localRunes[len(localRunes)-1]) + value[separator:]
}

func keyedDigest(key []byte, values ...string) []byte {
	digester := hmac.New(sha256.New, key)
	for index, value := range values {
		if index > 0 {
			digester.Write([]byte{0})
		}
		digester.Write([]byte(value))
	}
	return digester.Sum(nil)
}

func fingerprintOptional(key []byte, value string) []byte {
	if value == "" {
		return nil
	}

	return keyedDigest(key, value)
}

func (service *Service) digestCode(id string, purpose Purpose, code string) []byte {
	return keyedDigest(service.otpPepper, id, string(purpose), code)
}
