package emailverification

import (
	"errors"
	"fmt"
	"time"
)

var (
	ErrChallengeNotFound        = errors.New("email verification challenge not found")
	ErrDeliveryUnavailable      = errors.New("email verification delivery unavailable")
	ErrInvalidChallengeID       = errors.New("email verification challenge ID is invalid")
	ErrInvalidCode              = errors.New("email verification code is invalid")
	ErrInvalidEmail             = errors.New("email address is invalid")
	ErrInvalidOrExpiredCode     = errors.New("email verification code is invalid or expired")
	ErrInvalidPolicy            = errors.New("email verification policy is invalid")
	ErrInvalidPurpose           = errors.New("email verification purpose is invalid")
	ErrMissingClock             = errors.New("email verification clock is required")
	ErrMissingCodeGenerator     = errors.New("email verification code generator is required")
	ErrMissingFingerprintKey    = errors.New("email verification fingerprint key is required")
	ErrMissingIDGenerator       = errors.New("email verification ID generator is required")
	ErrMissingOTPPepper         = errors.New("email verification OTP pepper is required")
	ErrMissingPool              = errors.New("database pool is required")
	ErrMissingRepository        = errors.New("email verification repository is required")
	ErrMissingSender            = errors.New("email verification sender is required")
	ErrMissingService           = errors.New("email verification service is required")
	ErrRateLimited              = errors.New("email verification rate limited")
	ErrUnexpectedChallengeState = errors.New("email verification challenge state changed unexpectedly")
)

type RateLimitError struct {
	RetryAt time.Time
}

func (err *RateLimitError) Error() string {
	return fmt.Sprintf("%s until %s", ErrRateLimited, err.RetryAt.UTC().Format(time.RFC3339))
}

func (err *RateLimitError) Unwrap() error {
	return ErrRateLimited
}
